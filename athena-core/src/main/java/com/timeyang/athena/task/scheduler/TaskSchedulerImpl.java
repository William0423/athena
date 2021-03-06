package com.timeyang.athena.task.scheduler;

import com.timeyang.athena.AthenaConf;
import com.timeyang.athena.task.TaskInfo;
import com.timeyang.athena.task.TaskInfo.FinishedTask;
import com.timeyang.athena.task.TaskInfo.RunningTask;
import com.timeyang.athena.task.TaskInfo.WaitingTask;
import com.timeyang.athena.task.TaskRepository;
import com.timeyang.athena.task.TaskState;
import com.timeyang.athena.task.exec.LogManager;
import com.timeyang.athena.task.exec.TaskBackend;
import com.timeyang.athena.task.exec.TaskCallback;
import com.timeyang.athena.utill.jdbc.Page;
import com.timeyang.athena.utill.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author https://github.com/chaokunyang
 */
public class TaskSchedulerImpl implements TaskScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TaskSchedulerImpl.class);
    private static final long SCHEDULE_INTERVAL_SECONDS = 1;
    private static final long AWAIT_TERMINATION_SECONDS = 5;
    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(2);

    private final AthenaConf athenaConf;
    private final TaskRepository taskRepository;
    private final TaskBackend taskBackend;
    private final LogManager logManager;

    public TaskSchedulerImpl(AthenaConf athenaConf,
                             TaskRepository taskRepository) {
        this.athenaConf = athenaConf;
        this.taskRepository = taskRepository;

        TaskCallback callback = new TaskCallbackImpl();
        this.taskBackend = new TaskBackend(athenaConf, callback);
        this.logManager = new LogManager(athenaConf);
    }

    @Override
    public void start() {
        taskBackend.start();
        scheduledExecutorService.scheduleWithFixedDelay(
                this::scheduleWaitingTasks, 0, SCHEDULE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        LOGGER.info("Task scheduler started");
        checkRunningTasks();
    }

    @Override
    public void stop() {
        taskBackend.stop();
        scheduledExecutorService.shutdown();
        try {
            scheduledExecutorService.awaitTermination(AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS);
            LOGGER.info("Task scheduler stopped");
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void schedule(TaskInfo task) {
        if (!StringUtils.hasText(task.getHost())) {
            task.setHost(getTaskHost(task));
        }

        this.taskBackend.runTask(task);
    }

    @Override
    public boolean isTaskRunning(long taskId) {
        return this.taskBackend.isTaskRunning(taskId);
    }

    @Override
    public Future killTask(long taskId) {
        return this.taskBackend.killTask(taskId, () -> {
            Optional<RunningTask> optionalTask = this.taskRepository.getRunningTask(taskId);
            if (optionalTask.isPresent()) {
                RunningTask runningTask = optionalTask.get();
                FinishedTask finishedTask = new FinishedTask(runningTask);
                finishedTask.setState(TaskState.FAILED);

                taskRepository.moveToFinished(finishedTask);
                LOGGER.info("task [{}] killed", taskId);
            }
        });
    }

    @Override
    public List<String> getLogLines(long taskId, int lineNumber, int rows) {
        if (isTaskRunning(taskId)) {
            return taskBackend.getLogLines(taskId, lineNumber, rows);
        } else {
            return this.logManager.getLogLines(taskId, lineNumber, rows);
        }
    }

    private void scheduleWaitingTasks() {
        List<Page.Sort> sorts = Collections.singletonList(
                new Page.Sort("submit_time", Page.Order.ASC));
        Page page = new Page(0, 10, sorts);
        List<WaitingTask> waitingTasks = taskRepository
                .getWaitingTasks(page)
                .getElements();

        waitingTasks.forEach(task -> {
            // response thread interrupt
            if(!Thread.currentThread().isInterrupted())
                schedule(task);
        });
    }

    private void checkRunningTasks() {
        List<RunningTask> tasks = taskRepository.getAllRunningTasks();
        tasks.forEach(task -> {
            // if task is not running(maybe system restarted), schedule the task
            if (!taskBackend.isTaskRunning(task.getTaskId())) {
                LOGGER.info("task {} in table {} is not running, start it", task.getTaskId(), TaskRepository.RUNNING_TASK_TABLE);
                schedule(task);
            }
        });
    }

    /**
     * get host by task type, host available resources and roles
     * @param taskInfo task info
     * @return task exec host
     */
    private String getTaskHost(TaskInfo taskInfo) {
        return "localhost";
    }

    /**
     * @author https://github.com/chaokunyang
     */
    public class TaskCallbackImpl implements TaskCallback {

        private final ScheduledExecutorService scheduledExecutorService =
                Executors.newSingleThreadScheduledExecutor();

        @Override
        public void onStarted(long taskId, int pid) {
            Optional<RunningTask> optionalTask = taskRepository.getRunningTask(taskId);

            // task is not in running_task, implies that it's first time run
            if (!optionalTask.isPresent()) {
                WaitingTask waitingTask = taskRepository.getWaitingTask(taskId);
                RunningTask runningTask = new RunningTask(waitingTask);
                runningTask.setStartTime(Instant.now());
                runningTask.setTryNumber(1);
                runningTask.setPid(pid);

                taskRepository.moveToRunning(runningTask);
            } else { // task is in running_task, implies retrying
                RunningTask runningTask = optionalTask.get();
                runningTask.setPid(pid);
                int tryNumber = runningTask.getTryNumber() + 1;
                runningTask.setTryNumber(tryNumber);
                LOGGER.info("Task [{}] retry started, try number: {}", taskId, runningTask.getTryNumber() + 1);

                taskRepository.updateRunningTask(runningTask);
            }
        }

        @Override
        public void onSuccess(long taskId) {
            Optional<RunningTask> optionalInfo = taskRepository.getRunningTask(taskId);
            if (optionalInfo.isPresent()) {
                RunningTask runningTask = optionalInfo.get();
                FinishedTask finishedTask = new FinishedTask(runningTask);
                finishedTask.setState(TaskState.SUCCESS);

                taskRepository.moveToFinished(finishedTask);

                logManager.collect(runningTask);
            }
        }

        @Override
        public void onFailure(long taskId) {
            Optional<RunningTask> optionalTask = taskRepository.getRunningTask(taskId);
            if (optionalTask.isPresent()) { // task started, exec failed
                RunningTask runningTaskInfo = optionalTask.get();
                if (runningTaskInfo.getTryNumber() < runningTaskInfo.getMaxTries()) {
                    LOGGER.info("Task [{}] execute failed, retry it, try number: {}", taskId, runningTaskInfo.getTryNumber() + 1);
                    scheduledExecutorService.schedule(
                            () -> schedule(runningTaskInfo),
                            athenaConf.getDefaultTaskRetryWait(),
                            TimeUnit.SECONDS);
                } else {
                    FinishedTask finishedTask = new FinishedTask(runningTaskInfo);
                    finishedTask.setState(TaskState.FAILED);
                    taskRepository.moveToFinished(finishedTask);

                    logManager.collect(runningTaskInfo);
                }
            } else { // task started failed
                WaitingTask waitingTask = taskRepository.getWaitingTask(taskId);
                FinishedTask finishedTask = new FinishedTask(waitingTask);
                finishedTask.setState(TaskState.FAILED);
                finishedTask.setTryNumber(1);
                taskRepository.moveFromWaitingToFinished(finishedTask);
            }
        }

        @Override
        public void onLost(long taskId) {
            Optional<RunningTask> optionalTask = taskRepository.getRunningTask(taskId);

            if (optionalTask.isPresent()) {
                RunningTask runningTask = optionalTask.get();
                if (runningTask.getTryNumber() < runningTask.getMaxTries()) {
                    LOGGER.info("Task [{}] lost, retry it, try number: {}", taskId, runningTask.getTryNumber() + 1);
                    scheduledExecutorService.schedule(
                            () -> schedule(runningTask),
                            athenaConf.getDefaultTaskRetryWait(),
                            TimeUnit.SECONDS);
                } else {
                    FinishedTask finishedTask = new FinishedTask(runningTask);
                    finishedTask.setState(TaskState.LOST);
                    taskRepository.moveToFinished(finishedTask);

                    logManager.collect(finishedTask);
                }
            }
        }
    }
}
