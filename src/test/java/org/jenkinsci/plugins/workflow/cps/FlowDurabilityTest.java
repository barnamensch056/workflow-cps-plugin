package org.jenkinsci.plugins.workflow.cps;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.Result;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.actions.ArgumentsAction;
import org.jenkinsci.plugins.workflow.actions.LogAction;
import org.jenkinsci.plugins.workflow.actions.TimingAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepEndNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepNode;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.flow.FlowDurabilityHint;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.graph.FlowEndNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graph.FlowStartNode;
import org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.LinearScanner;
import org.jenkinsci.plugins.workflow.graphanalysis.NodeStepNamePredicate;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.support.storage.FlowNodeStorage;
import org.jenkinsci.plugins.workflow.support.storage.BulkFlowNodeStorage;
import org.jenkinsci.plugins.workflow.support.storage.SimpleXStreamFlowNodeStorage;
import org.jenkinsci.plugins.workflow.test.steps.SemaphoreStep;
import org.junit.Assert;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tests implementations designed to verify handling of the flow durability levels and persistence of pipeline state.
 *
 * <strong>This means:</strong>
 * <ol>
 *     <li>Persisting FlowNode data in an appropriately consistent fashion.</li>
 *     <li>Persisting Program data for running pipelines as appropriate.</li>
 *     <li>Not leaving orphaned durable tasks.</li>
 *     <li>Being able to load runs & FlowExecutions as appropriate, with up-to-date info.</li>
 *     <li>Being able to resume execution when we are supposed to.</li>
 * </ol>
 */
public class FlowDurabilityTest {
    @ClassRule
    public static BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public RestartableJenkinsRule story = new RestartableJenkinsRule();

    @Rule
    public TimedRepeatRule repeater = new TimedRepeatRule();

    // Used in testing
    static class TimedRepeatRule implements TestRule {

        @Target({ElementType.METHOD})
        @Retention(RetentionPolicy.RUNTIME)
        @interface RepeatForTime {
            long repeatMillis();
        }

        private static class RepeatedStatement extends Statement {
            private final Statement repeatedStmt;
            private final long repeatMillis;

            private RepeatedStatement(Statement stmt, long millis) {
                this.repeatedStmt = stmt;
                this.repeatMillis = millis;
            }

            @Override
            public void evaluate() throws Throwable {
                long start = System.currentTimeMillis();
                while(Math.abs(System.currentTimeMillis()-start)<repeatMillis) {
                    repeatedStmt.evaluate();
                }
            }
        }

        @Override
        public Statement apply(Statement statement, Description description) {
            RepeatForTime rep = description.getAnnotation(RepeatForTime.class);
            if (rep == null) {
                return statement;
            } else {
                return new RepeatedStatement(statement, rep.repeatMillis());
            }
        }
    }

    static WorkflowRun createAndRunBasicJob(Jenkins jenkins, String jobName, FlowDurabilityHint durabilityHint) throws Exception {
        return createAndRunBasicJob(jenkins, jobName, durabilityHint, 1);
    }

    static void assertBaseStorageType(FlowExecution exec, Class<? extends FlowNodeStorage> storageClass) throws Exception {
        if (exec instanceof CpsFlowExecution) {
            FlowNodeStorage store = ((CpsFlowExecution) exec).getStorage();
            if (store instanceof CpsFlowExecution.TimingFlowNodeStorage) {
                Field f = CpsFlowExecution.TimingFlowNodeStorage.class.getDeclaredField("delegate");
                f.setAccessible(true);
                FlowNodeStorage delegateStore = (FlowNodeStorage)(f.get(store));
                Assert.assertEquals(storageClass.toString(), delegateStore.getClass().toString());
            }
        }
    }

    /** Create and run a job with a semaphore and basic steps -- takes a semaphoreIndex in case you have multiple semaphores of the same name in one test.*/
    static WorkflowRun createAndRunBasicJob(Jenkins jenkins, String jobName, FlowDurabilityHint durabilityHint, int semaphoreIndex) throws Exception {
        WorkflowJob job = jenkins.createProject(WorkflowJob.class, jobName);
        CpsFlowDefinition def = new CpsFlowDefinition("node {\n " +
                "semaphore 'halt' \n" +
                "} \n" +
                "echo 'I like chese'\n", false);
        def.setDurabilityHint(durabilityHint);
        job.setDefinition(def);
        WorkflowRun run = job.scheduleBuild2(0).getStartCondition().get();
        SemaphoreStep.waitForStart("halt/"+semaphoreIndex, run);
        Assert.assertEquals(durabilityHint, run.getExecution().getDurabilityHint());
        if (durabilityHint.isPersistWithEveryStep()) {
            assertBaseStorageType(run.getExecution(), SimpleXStreamFlowNodeStorage.class);
        } else {
            assertBaseStorageType(run.getExecution(), BulkFlowNodeStorage.class);
        }
        Assert.assertEquals("semaphore", run.getExecution().getCurrentHeads().get(0).getDisplayFunctionName());
        return run;
    }

    static WorkflowRun createAndRunSleeperJob(Jenkins jenkins, String jobName, FlowDurabilityHint durabilityHint) throws Exception {
        Item prev = jenkins.getItemByFullName(jobName);
        if (prev != null) {
            prev.delete();
        }

        WorkflowJob job = jenkins.createProject(WorkflowJob.class, jobName);
        CpsFlowDefinition def = new CpsFlowDefinition("node {\n " +
                "sleep 30 \n" +
                "} \n" +
                "echo 'I like chese'\n", false);
        def.setDurabilityHint(durabilityHint);
        job.setDefinition(def);
        WorkflowRun run = job.scheduleBuild2(0).getStartCondition().get();
        Thread.sleep(4000L);  // Hacky but we just need to ensure this can start up
        Assert.assertEquals(durabilityHint, run.getExecution().getDurabilityHint());
        Assert.assertEquals("sleep", run.getExecution().getCurrentHeads().get(0).getDisplayFunctionName());
        return run;
    }

    static void verifySucceededCleanly(Jenkins j, WorkflowRun run) throws Exception {
        Assert.assertEquals(Result.SUCCESS, run.getResult());
        int outputHash = run.getLog().hashCode();
        FlowExecution exec = run.getExecution();
        verifyCompletedCleanly(j, run);

        // Confirm the flow graph is fully navigable and contains the heads with appropriate ending
        DepthFirstScanner scan = new DepthFirstScanner();
        List<FlowNode> allNodes = scan.allNodes(exec);
        FlowNode endNode = exec.getCurrentHeads().get(0);
        Assert.assertEquals(FlowEndNode.class, endNode.getClass());
        assert allNodes.contains(endNode);
        Assert.assertEquals(8, allNodes.size());

        // Graph structure assertions
        Assert.assertEquals(2, scan.filteredNodes(endNode, (Predicate)(Predicates.instanceOf(StepStartNode.class))).size());
        Assert.assertEquals(2, scan.filteredNodes(endNode, (Predicate)(Predicates.instanceOf(StepEndNode.class))).size());
        Assert.assertEquals(1, scan.filteredNodes(endNode, (Predicate)(Predicates.instanceOf(FlowStartNode.class))).size());

        Predicate<FlowNode> sleepOrSemaphoreMatch = Predicates.or(
                new NodeStepNamePredicate(StepDescriptor.byFunctionName("semaphore").getId()),
                new NodeStepNamePredicate(StepDescriptor.byFunctionName("sleep").getId())
        );
        Assert.assertEquals(1, scan.filteredNodes(endNode, sleepOrSemaphoreMatch).size());
        Assert.assertEquals(1, scan.filteredNodes(endNode, new NodeStepNamePredicate(StepDescriptor.byFunctionName("echo").getId())).size());

        for (FlowNode node : (List<FlowNode>)(scan.filteredNodes(endNode, (Predicate)(Predicates.instanceOf(StepNode.class))))) {
            Assert.assertNotNull("Node: "+node.toString()+" does not have a TimingAction", node.getAction(TimingAction.class));
        }
    }

    /** If it's a {@link SemaphoreStep} we test less rigorously because that blocks async GraphListeners. */
    static void verifySafelyResumed(JenkinsRule rule, WorkflowRun run, boolean isSemaphore, String logStart) throws Exception {
        assert run.isBuilding();
        FlowExecution exec = run.getExecution();

        // Assert that we have the appropriate flow graph entries
        List<FlowNode> heads = exec.getCurrentHeads();
        Assert.assertEquals(1, heads.size());
        FlowNode node = heads.get(0);
        String name = node.getDisplayFunctionName();
        Assert.assertTrue("Head node not a semaphore step or sleep: "+name, "semaphore".equals(name) || "sleep".equals(name));
        if (!isSemaphore) {
            Assert.assertNotNull(node.getPersistentAction(TimingAction.class));
            Assert.assertNotNull(node.getPersistentAction(ArgumentsAction.class));
            Assert.assertNotNull(node.getAction(LogAction.class));
        } else {
            SemaphoreStep.success("halt/1", Result.SUCCESS);
        }

        rule.waitForCompletion(run);
        verifySucceededCleanly(rule.jenkins, run);
        rule.assertLogContains(logStart, run);
    }

    /** Waits until the build to resume or die. */
    static void waitForBuildToResumeOrFail(WorkflowRun run) throws TimeoutException {
        CpsFlowExecution execution = (CpsFlowExecution)(run.getExecution());
        long nanoStartTime = System.nanoTime();
        while (true) {
            if (!run.isBuilding()) {
                return;
            }
            long currentTime = System.nanoTime();
            if (TimeUnit.SECONDS.convert(currentTime-nanoStartTime, TimeUnit.NANOSECONDS) > 5) {
                throw new TimeoutException();
            }
        }
    }

    static void verifyFailedCleanly(Jenkins j, WorkflowRun run) throws Exception {

        if (run.isBuilding()) {  // Give the run a little bit of time to see if it can resume or not
            FlowExecution exec = run.getExecution();
            if (exec instanceof CpsFlowExecution) {
                waitForBuildToResumeOrFail(run);
            } else {
                Thread.sleep(4000L);
            }
        }

        if (run.getExecution() instanceof CpsFlowExecution) {
            CpsFlowExecution cfe = (CpsFlowExecution)(run.getExecution());
            assert cfe.isComplete() || (cfe.programPromise != null && cfe.programPromise.isDone());
        }

        assert !run.isBuilding();  // This test flakes sometimes!  This needs to be FIXED.  Why does it still show as "isBuilding"?

        if (run.getExecution() instanceof  CpsFlowExecution) {
            Assert.assertEquals(Result.FAILURE, ((CpsFlowExecution) run.getExecution()).getResult());
        }

        // FIXME how does the FlowExecution bubble result back up to the WorkfloWRun

        Assert.assertEquals(Result.FAILURE, run.getResult());
        assert !run.isBuilding();
        // TODO verify all blocks cleanly closed out, so Block start and end nodes have same counts and FlowEndNode is last node
        verifyCompletedCleanly(j, run);
    }

    /** Verifies all the universal post-build cleanup was done, regardless of pass/fail state. */
    static void verifyCompletedCleanly(Jenkins j, WorkflowRun run) throws Exception {
        // Assert that we have the appropriate flow graph entries
        FlowExecution exec = run.getExecution();
        List<FlowNode> heads = exec.getCurrentHeads();
        Assert.assertEquals(1, heads.size());
        verifyNoTasksRunning(j);
        Assert.assertEquals(0, exec.getCurrentExecutions(false).get().size());

        if (exec instanceof CpsFlowExecution) {
            CpsFlowExecution cpsFlow = (CpsFlowExecution)exec;
            assert cpsFlow.getStorage() != null;
            Assert.assertFalse("Should always be able to retrieve script", StringUtils.isEmpty(cpsFlow.getScript()));
            Assert.assertNull("We should have no Groovy shell left or that's a memory leak", cpsFlow.getShell());
            Assert.assertNull("We should have no Groovy shell left or that's a memory leak", cpsFlow.getTrustedShell());
        }
    }

    /** Verifies we have nothing left that uses an executor for a given job. */
    static void verifyNoTasksRunning(Jenkins j) {
        assert j.getQueue().isEmpty();
        Computer[] computerList = j.getComputers();
        for (Computer c : computerList) {
            List<Executor> executors = c.getExecutors();
            for (Executor ex : executors) {
                if (ex.isBusy()) {
                    Assert.fail("Computer "+c+" has an Executor "+ex+" still running a task: "+ex.getCurrentWorkUnit());
                }
            }
        }
    }

    /**
     * Confirm that for ALL implementations, a run can complete and be loaded if you restart after completion.
     */
    @Test
    public void testCompleteAndLoadBuilds() throws Exception {
        final FlowDurabilityHint[] durabilityHints = FlowDurabilityHint.values();
        final WorkflowJob[] jobs = new WorkflowJob[durabilityHints.length];
        final String[] logOutput = new String[durabilityHints.length];

        // Create and run jobs for each of the durability hints
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                int i = 1;
                for (FlowDurabilityHint hint : durabilityHints) {
                    try{
                        WorkflowRun run = createAndRunBasicJob(story.j.jenkins, "basicJob-"+hint.toString(), hint, i);
                        jobs[i-1] = run.getParent();
                        SemaphoreStep.success("halt/"+i++,Result.SUCCESS);
                        story.j.waitForCompletion(run);
                        story.j.assertBuildStatus(Result.SUCCESS, run);
                        logOutput[i-2] = JenkinsRule.getLog(run);
                    } catch (AssertionError ae) {
                        System.out.println("Error with durability level: "+hint);
                        throw ae;
                    }
                }
            }
        });

        //Restart and confirm we can still load them.
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                for (int i=0; i<durabilityHints.length; i++) {
                    WorkflowJob j  = jobs[i];
                    try{
                        WorkflowRun run = j.getLastBuild();
                        verifySucceededCleanly(story.j.jenkins, run);
                        Assert.assertEquals(durabilityHints[i], run.getExecution().getDurabilityHint());
                        Assert.assertEquals(logOutput[i], JenkinsRule.getLog(run));
                    } catch (AssertionError ae) {
                        System.out.println("Error with durability level: "+j.getDefinition().getDurabilityHint());
                        throw ae;
                    }
                }
            }
        });
    }

    /**
     * Verifies that if we're only durable against clean restarts, the pipeline will survive it.
     */
    @Test
    public void testDurableAgainstCleanRestartSurvivesIt() throws Exception {
        final String jobName = "durableAgainstClean";
        final String[] logStart = new String[1];

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Jenkins jenkins = story.j.jenkins;
                WorkflowRun run = createAndRunSleeperJob(story.j.jenkins, jobName, FlowDurabilityHint.PERFORMANCE_OPTIMIZED);
                FlowExecution exec = run.getExecution();
                assertBaseStorageType(exec, BulkFlowNodeStorage.class);
                logStart[0] = JenkinsRule.getLog(run);
            }
        });

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = story.j.jenkins.getItemByFullName(jobName, WorkflowJob.class).getLastBuild();
                Assert.assertEquals(FlowDurabilityHint.PERFORMANCE_OPTIMIZED, run.getExecution().getDurabilityHint());
                assertBaseStorageType(run.getExecution(), BulkFlowNodeStorage.class);
                verifySafelyResumed(story.j, run, true, logStart[0]);
            }
        });
    }

    /**
     * Verifies that paused pipelines survive dirty restarts
     */
    @Test
    public void testPauseForcesLowDurabilityToPersist() throws Exception {
        final String jobName = "durableAgainstClean";
        final String[] logStart = new String[1];

        story.addStepWithDirtyShutdown(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Jenkins jenkins = story.j.jenkins;
                WorkflowRun run = createAndRunSleeperJob(story.j.jenkins, jobName, FlowDurabilityHint.PERFORMANCE_OPTIMIZED);
                FlowExecution exec = run.getExecution();
                assertBaseStorageType(exec, BulkFlowNodeStorage.class);
                logStart[0] = JenkinsRule.getLog(run);
                if (run.getExecution() instanceof CpsFlowExecution) {
                    CpsFlowExecution cpsFlow = (CpsFlowExecution)(run.getExecution());
                    cpsFlow.pause(true);
                    long timeout = System.nanoTime()+TimeUnit.NANOSECONDS.convert(5, TimeUnit.SECONDS);
                    while(System.nanoTime() < timeout && !cpsFlow.isPaused()) {
                        Thread.sleep(100L);
                    }
                }
            }
        });

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = story.j.jenkins.getItemByFullName(jobName, WorkflowJob.class).getLastBuild();
                Assert.assertEquals(FlowDurabilityHint.PERFORMANCE_OPTIMIZED, run.getExecution().getDurabilityHint());
                assertBaseStorageType(run.getExecution(), BulkFlowNodeStorage.class);
                verifySafelyResumed(story.j, run, true, logStart[0]);
            }
        });
    }

    /** Verify that our flag for whether or not a build was cleanly persisted gets reset when things happen.
     */
    @Test
    public void testDurableAgainstCleanRestartResetsCleanlyPersistedFlag() throws Exception {
        final String jobName = "durableAgainstClean";
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowJob job = story.j.jenkins.createProject(WorkflowJob.class, jobName);
                CpsFlowDefinition def = new CpsFlowDefinition("node {\n " +
                        "  sleep 30 \n" +
                        "  dir('nothing'){sleep 30;}\n"+
                        "} \n" +
                        "echo 'I like chese'\n", false);
                def.setDurabilityHint(FlowDurabilityHint.PERFORMANCE_OPTIMIZED);
                job.setDefinition(def);
                WorkflowRun run = job.scheduleBuild2(0).getStartCondition().get();
                Thread.sleep(2000L);  // Hacky but we just need to ensure this can start up
            }
        });
        story.addStepWithDirtyShutdown(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = story.j.jenkins.getItemByFullName(jobName, WorkflowJob.class).getLastBuild();
                assert run.isBuilding();
                assert run.getResult() != Result.FAILURE;
                Thread.sleep(35000);  // Step completes
                if (run.getExecution() instanceof  CpsFlowExecution) {
                    CpsFlowExecution exec = (CpsFlowExecution)run.getExecution();
                    assert exec.persistedClean == null;
                }
            }
        });
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Thread.sleep(2000L);  // Just to allow time for basic async processes to finish.
               verifyFailedCleanly(story.j.jenkins, story.j.jenkins.getItemByFullName(jobName, WorkflowJob.class).getLastBuild());
            }
        });
    }


    /** Verify that if the master dies messily and we're not durable against that, build fails cleanly.
     */
    @Test
    public void testDurableAgainstCleanRestartFailsWithDirtyShutdown() throws Exception {
        final String[] logStart = new String[1];
        story.addStepWithDirtyShutdown(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = createAndRunSleeperJob(story.j.jenkins, "durableAgainstClean", FlowDurabilityHint.PERFORMANCE_OPTIMIZED);
                Assert.assertEquals(FlowDurabilityHint.PERFORMANCE_OPTIMIZED, run.getExecution().getDurabilityHint());
                logStart[0] = JenkinsRule.getLog(run);
            }
        });

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = story.j.jenkins.getItemByFullName("durableAgainstClean", WorkflowJob.class).getLastBuild();
                verifyFailedCleanly(story.j.jenkins, run);
                story.j.assertLogContains(logStart[0], run);
            }
        });
    }

    /** Veryify that if we bomb out because we cannot resume, we at least try to finish the flow graph */
    @Test
    public void testPipelineFinishesFlowGraph() throws Exception {
        final String[] logStart = new String[1];
        final List<FlowNode> nodesOut = new ArrayList<FlowNode>();
        story.addStepWithDirtyShutdown(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = createAndRunSleeperJob(story.j.jenkins, "durableAgainstClean", FlowDurabilityHint.PERFORMANCE_OPTIMIZED);
                Assert.assertEquals(FlowDurabilityHint.PERFORMANCE_OPTIMIZED, run.getExecution().getDurabilityHint());
                logStart[0] = JenkinsRule.getLog(run);
                if (run.getExecution() instanceof CpsFlowExecution) {
                    // Pause and unPause to force persistence
                    CpsFlowExecution cpsFlow = (CpsFlowExecution)(run.getExecution());
                    cpsFlow.pause(true);
                    long timeout = System.nanoTime()+TimeUnit.NANOSECONDS.convert(5, TimeUnit.SECONDS);
                    while(System.nanoTime() < timeout && !cpsFlow.isPaused()) {
                        Thread.sleep(100L);
                    }
                    nodesOut.addAll(new LinearScanner().allNodes(run.getExecution()));
                    Collections.reverse(nodesOut);
                    cpsFlow.pause(false);
                    timeout = System.nanoTime()+TimeUnit.NANOSECONDS.convert(5, TimeUnit.SECONDS);
                    while(System.nanoTime() < timeout && cpsFlow.isPaused()) {
                        Thread.sleep(100L);
                    }

                    // Ensures we're marked as can-not-resume
                    cpsFlow.persistedClean = false;
                    cpsFlow.saveOwner();
                }
            }
        });

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = story.j.jenkins.getItemByFullName("durableAgainstClean", WorkflowJob.class).getLastBuild();
                verifyFailedCleanly(story.j.jenkins, run);
                story.j.assertLogContains(logStart[0], run);
                List<FlowNode> nodes = new LinearScanner().allNodes(run.getExecution());
                Collections.reverse(nodes);

                // Make sure we have the starting nodes at least
                assert  nodesOut.size() > nodes.size();
                for (int i=0; i<nodesOut.size(); i++) {
                    try {
                        FlowNode match = nodesOut.get(i);
                        FlowNode after = nodes.get(i);
                        Assert.assertEquals(match.getDisplayFunctionName(), after.getDisplayFunctionName());
                    } catch (Exception ex) {
                        throw new Exception("Error with flownode at index="+i, ex);
                    }
                }
            }
        });
    }

    /** Sanity check that fully durable pipelines shutdown and restart cleanly */
    @Test
    public void testFullyDurableSurvivesCleanRestart() throws Exception {
        final String jobName = "survivesEverything";
        final String[] logStart = new String[1];

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Jenkins jenkins = story.j.jenkins;
                WorkflowRun run = createAndRunBasicJob(story.j.jenkins, jobName, FlowDurabilityHint.MAX_SURVIVABILITY);
                FlowExecution exec = run.getExecution();
                if (exec instanceof CpsFlowExecution) {
                    assert ((CpsFlowExecution) exec).getStorage().isPersistedFully();
                }
                logStart[0] = JenkinsRule.getLog(run);
            }
        });

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = story.j.jenkins.getItemByFullName(jobName, WorkflowJob.class).getLastBuild();
                verifySafelyResumed(story.j, run, true, logStart[0]);
            }
        });
    }

    /**
     * Sanity check that fully durable pipelines can survive hard kills.
     */
    @Test
    public void testFullyDurableSurvivesDirtyRestart() throws Exception {
        final String jobName = "survivesEverything";
        final String[] logStart = new String[1];

        story.addStepWithDirtyShutdown(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Jenkins jenkins = story.j.jenkins;
                WorkflowRun run = createAndRunSleeperJob(story.j.jenkins, jobName, FlowDurabilityHint.MAX_SURVIVABILITY);
                FlowExecution exec = run.getExecution();
                if (exec instanceof CpsFlowExecution) {
                    assert ((CpsFlowExecution) exec).getStorage().isPersistedFully();
                }
                logStart[0] = JenkinsRule.getLog(run);
            }
        });

        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowRun run = story.j.jenkins.getItemByFullName(jobName, WorkflowJob.class).getLastBuild();
                verifySafelyResumed(story.j, run, false, logStart[0]);
            }
        });
    }

    @Test
    @Ignore
    @TimedRepeatRule.RepeatForTime(repeatMillis = 300_000)
    public void fuzzTesting() throws Exception {

        long startTime = System.nanoTime();

        // Create thread that eventually interrupts Jenkins with a hard shutdown at a random time interval
        story.addStepWithDirtyShutdown(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Jenkins jenkins = story.j.jenkins;
                WorkflowRun run = createAndRunSleeperJob(story.j.jenkins, "bob", FlowDurabilityHint.MAX_SURVIVABILITY);
                FlowExecution exec = run.getExecution();
                if (exec instanceof CpsFlowExecution) {
                    assert ((CpsFlowExecution) exec).getStorage().isPersistedFully();
                }
            }
        });

    }
}
