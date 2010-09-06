/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hama.bsp;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.Server;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.ipc.InterTrackerProtocol;
import org.apache.hama.ipc.JobSubmissionProtocol;

/**
 * BSPMaster is responsible to control all the groom servers and to manage bsp
 * jobs.
 */
public class BSPMaster implements JobSubmissionProtocol, InterTrackerProtocol,
    GroomServerManager {
  public static final Log LOG = LogFactory.getLog(BSPMaster.class);

  private Configuration conf;

  // Constants
  public static enum State {
    INITIALIZING, RUNNING
  }

  private static final int FS_ACCESS_RETRY_PERIOD = 10000;
  public static final long GROOMSERVER_EXPIRY_INTERVAL = 10 * 60 * 1000;

  // States
  State state = State.INITIALIZING;

  // Attributes
  String masterIdentifier;
  private Server interTrackerServer;

  // Filesystem
  static final String SUBDIR = "bspMaster";
  FileSystem fs = null;
  Path systemDir = null;
  // system directories are world-wide readable and owner readable
  final static FsPermission SYSTEM_DIR_PERMISSION = FsPermission
      .createImmutable((short) 0733); // rwx-wx-wx
  // system files should have 700 permission
  final static FsPermission SYSTEM_FILE_PERMISSION = FsPermission
      .createImmutable((short) 0700); // rwx------

  // Groom Servers
  // (groom name --> last sent HeartBeatResponse)
  Map<String, HeartbeatResponse> groomToHeartbeatResponseMap = new TreeMap<String, HeartbeatResponse>();
  private HashMap<String, GroomServerStatus> groomServers = new HashMap<String, GroomServerStatus>();

  // Jobs' Meta Data
  private int nextJobId = 1;
  // private long startTime;
  private int totalSubmissions = 0;
  private int totalTasks = 0;
  private int totalTaskCapacity;
  private Map<BSPJobID, JobInProgress> jobs = new TreeMap<BSPJobID, JobInProgress>();
  private TaskScheduler taskScheduler;

  ExpireLaunchingTasks expireLaunchingTasks = new ExpireLaunchingTasks();
  Thread expireLaunchingTaskThread = new Thread(expireLaunchingTasks,
      "expireLaunchingTasks");

  private class ExpireLaunchingTasks implements Runnable {
    private volatile boolean shouldRun = true;
    private Map<String, Long> launchingTasks = new LinkedHashMap<String, Long>();

    @Override
    public void run() {
      while (shouldRun) {
        long now = System.currentTimeMillis();

        synchronized (BSPMaster.this) {
          synchronized (launchingTasks) {
            Iterator<Entry<String, Long>> itr = launchingTasks.entrySet()
                .iterator();
            while (itr.hasNext()) {
              Map.Entry<String, Long> pair = itr.next();
              String taskId = pair.getKey();
              long age = now - ((Long) pair.getValue()).longValue();
              LOG.debug(taskId + " is " + age + " ms debug.");

              LOG.info(taskId);
              if (age > GROOMSERVER_EXPIRY_INTERVAL) {
                LOG.info("Launching task " + taskId + " timed out.");
                TaskInProgress tip = null;
                tip = (TaskInProgress) taskidToTIPMap.get(taskId);
                if (tip != null) {
                  JobInProgress job = tip.getJob();
                  String groomName = getAssignedTracker(taskId);
                  GroomServerStatus trackerStatus = getGroomServer(groomName);
                  // This might happen when the tasktracker has already
                  // expired and this thread tries to call failedtask
                  // again. expire tasktracker should have called failed
                  // task!
                  if (trackerStatus != null) {
                    /*
                     * job.failedTask(tip, taskId, "Error launching task",
                     * tip.isMapTask()? TaskStatus.Phase.MAP:
                     * TaskStatus.Phase.STARTING, trackerStatus.getHost(),
                     * trackerName, myMetrics);
                     */
                  }
                }
                itr.remove();
              } else {
                // the tasks are sorted by start time, so once we find
                // one that we want to keep, we are done for this cycle.
                break;
              }

            }
          }
        }
      }
    }

    private String getAssignedTracker(String taskId) {
      return taskidToTrackerMap.get(taskId);
    }

    public void addNewTask(String string) {
      synchronized (launchingTasks) {
        launchingTasks.put(string, new Long(System.currentTimeMillis()));
      }
    }

    public void stop() {
      shouldRun = false;
    }

  }

  /**
   * Start the BSPMaster process, listen on the indicated hostname/port
   */
  public BSPMaster(Configuration conf) throws IOException, InterruptedException {
    this(conf, generateNewIdentifier());
  }

  BSPMaster(Configuration conf, String identifier) throws IOException,
      InterruptedException {
    this.conf = conf;
    this.masterIdentifier = identifier;
    // expireLaunchingTaskThread.start();

    // Create the scheduler
    Class<? extends TaskScheduler> schedulerClass = conf.getClass(
        "bsp.master.taskscheduler", SimpleTaskScheduler.class,
        TaskScheduler.class);
    this.taskScheduler = (TaskScheduler) ReflectionUtils.newInstance(
        schedulerClass, conf);

    InetSocketAddress addr = getAddress(conf);
    this.interTrackerServer = RPC.getServer(this, addr.getHostName(), addr
        .getPort(), conf);

    while (!Thread.currentThread().isInterrupted()) {
      try {
        if (fs == null) {
          fs = FileSystem.get(conf);
        }
        // clean up the system dir, which will only work if hdfs is out of
        // safe mode
        if (systemDir == null) {
          systemDir = new Path(getSystemDir());
        }

        LOG.info("Cleaning up the system directory");
        LOG.info(systemDir);
        fs.delete(systemDir, true);
        if (FileSystem.mkdirs(fs, systemDir, new FsPermission(
            SYSTEM_DIR_PERMISSION))) {
          break;
        }
        LOG.error("Mkdirs failed to create " + systemDir);
        LOG.info(SUBDIR);

      } catch (AccessControlException ace) {
        LOG.warn("Failed to operate on bsp.system.dir (" + systemDir
            + ") because of permissions.");
        LOG.warn("Manually delete the bsp.system.dir (" + systemDir
            + ") and then start the BSPMaster.");
        LOG.warn("Bailing out ... ");
        throw ace;
      } catch (IOException ie) {
        LOG.info("problem cleaning system directory: " + systemDir, ie);
      }
      Thread.sleep(FS_ACCESS_RETRY_PERIOD);
    }

    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedException();
    }

    deleteLocalFiles(SUBDIR);
  }

  // /////////////////////////////////////////////////////
  // Accessors for objects that want info on jobs, tasks,
  // grooms, etc.
  // /////////////////////////////////////////////////////
  public GroomServerStatus getGroomServer(String groomID) {
    synchronized (groomServers) {
      return groomServers.get(groomID);
    }
  }

  public List<String> groomServerNames() {
    List<String> activeGrooms = new ArrayList<String>();
    synchronized (groomServers) {
      for (GroomServerStatus status : groomServers.values()) {
        activeGrooms.add(status.getGroomName());
      }
    }
    return activeGrooms;
  }

  // /////////////////////////////////////////////////////////////
  // BSPMaster methods
  // /////////////////////////////////////////////////////////////

  // Get the job directory in system directory
  Path getSystemDirectoryForJob(BSPJobID id) {
    return new Path(getSystemDir(), id.toString());
  }

  String[] getLocalDirs() throws IOException {
    return conf.getStrings("bsp.local.dir");
  }

  void deleteLocalFiles() throws IOException {
    String[] localDirs = getLocalDirs();
    for (int i = 0; i < localDirs.length; i++) {
      FileSystem.getLocal(conf).delete(new Path(localDirs[i]), true);
    }
  }

  void deleteLocalFiles(String subdir) throws IOException {
    try {
      String[] localDirs = getLocalDirs();
      for (int i = 0; i < localDirs.length; i++) {
        FileSystem.getLocal(conf).delete(new Path(localDirs[i], subdir), true);
      }
    } catch (NullPointerException e) {
      LOG.info(e);
    }
  }

  /**
   * Constructs a local file name. Files are distributed among configured local
   * directories.
   */
  Path getLocalPath(String pathString) throws IOException {
    return conf.getLocalPath("bsp.local.dir", pathString);
  }

  public static BSPMaster startMaster(HamaConfiguration conf)
      throws IOException, InterruptedException {
    return startTracker(conf, generateNewIdentifier());
  }

  public static BSPMaster startTracker(Configuration conf, String identifier)
      throws IOException, InterruptedException {

    BSPMaster result = null;
    result = new BSPMaster(conf, identifier);
    result.taskScheduler.setGroomServerManager(result);

    return result;
  }

  public static InetSocketAddress getAddress(Configuration conf) {
    String hamaMasterStr = conf.get("bsp.master.address", "localhost:40000");
    return NetUtils.createSocketAddr(hamaMasterStr);
  }

  public int getPort() {
    return this.conf.getInt("bsp.master.port", 0);
  }

  public Configuration getConf() {
    return this.conf;
  }

  private static SimpleDateFormat getDateFormat() {
    return new SimpleDateFormat("yyyyMMddHHmm");
  }

  /**
   * 
   * @return
   */
  private static String generateNewIdentifier() {
    return getDateFormat().format(new Date());
  }

  public void offerService() throws InterruptedException, IOException {
    this.interTrackerServer.start();

    synchronized (this) {
      state = State.RUNNING;
    }
    LOG.info("Starting RUNNING");

    this.interTrackerServer.join();
    LOG.info("Stopped interTrackerServer");
  }

  // //////////////////////////////////////////////////
  // GroomServerManager
  // //////////////////////////////////////////////////
  @Override
  public void addJobInProgressListener(JobInProgressListener listener) {
    // jobInProgressListeners.add(listener);
  }

  @Override
  public void removeJobInProgressListener(JobInProgressListener listener) {
    // jobInProgressListeners.remove(listener);
  }

  @Override
  public void failJob(JobInProgress job) {
    // TODO Auto-generated method stub
  }

  @Override
  public JobInProgress getJob(BSPJobID jobid) {
    return jobs.get(jobid);
  }

  @Override
  public int getNextHeartbeatInterval() {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public int getNumberOfUniqueHosts() {
    // TODO Auto-generated method stub
    return 1;
  }

  @Override
  public Collection<GroomServerStatus> grooms() {
    return groomServers.values();
  }

  @Override
  public void initJob(JobInProgress job) {
    if (null == job) {
      LOG.info("Init on null job is not valid");
      return;
    }

    // JobStatus prevStatus = (JobStatus)job.getStatus().clone();
    LOG.info("Initializing " + job.getJobID());
  }

  // //////////////////////////////////////////////////
  // InterTrackerProtocol
  // //////////////////////////////////////////////////
  @Override
  public long getProtocolVersion(String protocol, long clientVersion)
      throws IOException {
    if (protocol.equals(InterTrackerProtocol.class.getName())) {
      return InterTrackerProtocol.versionID;
    } else if (protocol.equals(JobSubmissionProtocol.class.getName())) {
      return JobSubmissionProtocol.versionID;
    } else {
      throw new IOException("Unknown protocol to job tracker: " + protocol);
    }
  }

  /**
   * A RPC method for transmitting each peer status from peer to master.
   * 
   * @throws IOException
   */
  @Override
  public HeartbeatResponse heartbeat(GroomServerStatus status,
      boolean restarted, boolean initialContact, boolean acceptNewTasks,
      short responseId) throws IOException {

    // First check if the last heartbeat response got through
    String groomName = status.getGroomName();
    long now = System.currentTimeMillis();

    HeartbeatResponse prevHeartbeatResponse = groomToHeartbeatResponseMap
        .get(groomName);

    // Process this heartbeat
    short newResponseId = (short) (responseId + 1);
    status.setLastSeen(now);
    if (!processHeartbeat(status, initialContact)) {
      if (prevHeartbeatResponse != null) {
        groomToHeartbeatResponseMap.remove(groomName);
      }
      return new HeartbeatResponse(newResponseId,
          new GroomServerAction[] { new ReinitTrackerAction() });
    }

    HeartbeatResponse response = new HeartbeatResponse(newResponseId, null);
    List<GroomServerAction> actions = new ArrayList<GroomServerAction>();

    // Check for new tasks to be executed on the groom server
    if (acceptNewTasks) {
      GroomServerStatus groomStatus = getGroomServer(groomName);
      if (groomStatus == null) {
        LOG.warn("Unknown task tracker polling; ignoring: " + groomName);
      } else {
        List<Task> tasks = taskScheduler.assignTasks(groomStatus);
        for (Task task : tasks) {
          if (tasks != null) {
            expireLaunchingTasks.addNewTask(task.getTaskID());
            actions.add(new LaunchTaskAction(task));
          }
        }
      }
    }

    // Check for tasks to be killed
    List<GroomServerAction> killTasksList = getTasksToKill(groomName);
    if (killTasksList != null) {
      actions.addAll(killTasksList);
    }

    response.setActions(actions.toArray(new GroomServerAction[actions.size()]));

    groomToHeartbeatResponseMap.put(groomName, response);
    removeMarkedTasks(groomName);

    return response;
  }

  // (trackerID -> TreeSet of completed taskids running at that tracker)
  TreeMap<String, Set<String>> trackerToMarkedTasksMap = new TreeMap<String, Set<String>>();

  private void removeMarkedTasks(String groomName) {
    // Purge all the 'marked' tasks which were running at taskTracker
    TreeSet<String> markedTaskSet = (TreeSet<String>) trackerToMarkedTasksMap
        .get(groomName);
    if (markedTaskSet != null) {
      for (String taskid : markedTaskSet) {
        removeTaskEntry(taskid);
        LOG.info("Removed completed task '" + taskid + "' from '" + groomName
            + "'");
      }
      // Clear
      trackerToMarkedTasksMap.remove(groomName);
    }
  }

  private void removeTaskEntry(String taskid) {
    // taskid --> tracker
    String tracker = taskidToTrackerMap.remove(taskid);

    // tracker --> taskid
    if (tracker != null) {
      TreeSet trackerSet = (TreeSet) trackerToTaskMap.get(tracker);
      if (trackerSet != null) {
        trackerSet.remove(taskid);
      }
    }

    // taskid --> TIP
    taskidToTIPMap.remove(taskid);

    LOG.debug("Removing task '" + taskid + "'");
  }

  private List<GroomServerAction> getTasksToKill(String groomName) {
    Set<String> taskIds = (TreeSet<String>) trackerToTaskMap.get(groomName);
    if (taskIds != null) {
      List<GroomServerAction> killList = new ArrayList<GroomServerAction>();
      Set<String> killJobIds = new TreeSet<String>();
      for (String killTaskId : taskIds) {
        TaskInProgress tip = (TaskInProgress) taskidToTIPMap.get(killTaskId);
        if (tip.shouldCloseForClosedJob(killTaskId)) {
          // 
          // This is how the JobTracker ends a task at the TaskTracker.
          // It may be successfully completed, or may be killed in
          // mid-execution.
          //
          if (tip.getJob().getStatus().getRunState() == JobStatus.RUNNING) {
            killList.add(new KillTaskAction(killTaskId));
            LOG.debug(groomName + " -> KillTaskAction: " + killTaskId);
          } else {
            String killJobId = tip.getJob().getStatus().getJobID()
                .getJtIdentifier();
            killJobIds.add(killJobId);
          }
        }
      }

      for (String killJobId : killJobIds) {
        killList.add(new KillJobAction(killJobId));
        LOG.debug(groomName + " -> KillJobAction: " + killJobId);
      }

      return killList;
    }
    return null;

  }

  /**
   * Process incoming heartbeat messages from the groom.
   */
  private synchronized boolean processHeartbeat(GroomServerStatus groomStatus,
      boolean initialContact) {
    String groomName = groomStatus.getGroomName();

    synchronized (groomServers) {
      GroomServerStatus oldStatus = groomServers.get(groomName);
      if (oldStatus == null) {
        groomServers.put(groomName, groomStatus);
      } else { // TODO - to be improved to update status.
      }
    }

    return true;
  }

  // //////////////////////////////////////////////////
  // JobSubmissionProtocol
  // //////////////////////////////////////////////////
  /**
   * This method returns new job id. The returned job id increases sequentially.
   */
  @Override
  public BSPJobID getNewJobId() throws IOException {
    return new BSPJobID(this.masterIdentifier, nextJobId++);
  }

  @Override
  public JobStatus submitJob(BSPJobID jobID, String jobFile) throws IOException {
    if (jobs.containsKey(jobID)) {
      // job already running, don't start twice
      LOG.info("The job (" + jobID + ") is already subbmitted");
      return jobs.get(jobID).getStatus();
    }

    JobInProgress job = new JobInProgress(jobID, this, this.conf);
    return addJob(jobID, job);
  }

  @Override
  public ClusterStatus getClusterStatus(boolean detailed) {
    synchronized (groomServers) {
      if (detailed) {
        List<String> groomNames = groomServerNames();
        return new ClusterStatus(groomNames, totalTasks, totalTaskCapacity,
            state);
      } else {
        return new ClusterStatus(groomServers.size(), totalTasks,
            totalTaskCapacity, state);
      }
    }
  }

  /**
   * Adds a job to the bsp master. Make sure that the checks are inplace before
   * adding a job. This is the core job submission logic
   * 
   * @param jobId The id for the job submitted which needs to be added
   */
  private synchronized JobStatus addJob(BSPJobID jodId, JobInProgress job) {
    totalSubmissions++;
    synchronized (jobs) {
      jobs.put(job.getProfile().getJobID(), job);
      taskScheduler.addJob(job);
    }
    return job.getStatus();
  }

  @Override
  public JobStatus[] getAllJobs() throws IOException {
    return null;
  }

  @Override
  public synchronized String getFilesystemName() throws IOException {
    if (fs == null) {
      throw new IllegalStateException("FileSystem object not available yet");
    }
    return fs.getUri().toString();
  }

  /**
   * Return system directory to which BSP store control files.
   */
  @Override
  public String getSystemDir() {
    Path sysDir = new Path(conf.get("bsp.system.dir", "/tmp/hadoop/bsp/system"));
    return fs.makeQualified(sysDir).toString();
  }

  @Override
  public JobProfile getJobProfile(BSPJobID jobid) throws IOException {
    synchronized (this) {
      JobInProgress job = jobs.get(jobid);
      if (job != null) {
        return job.getProfile();
      }
    }
    return null;
  }

  @Override
  public JobStatus getJobStatus(BSPJobID jobid) throws IOException {
    synchronized (this) {
      JobInProgress job = jobs.get(jobid);
      if (job != null) {
        return job.getStatus();
      }
    }
    return null;
  }

  @Override
  public void killJob(BSPJobID jobid) throws IOException {
    JobInProgress job = jobs.get(jobid);

    if (null == job) {
      LOG.info("killJob(): JobId " + jobid.toString() + " is not a valid job");
      return;
    }

    killJob(job);
  }

  private synchronized void killJob(JobInProgress job) {
    LOG.info("Killing job " + job.getJobID());
  }

  @Override
  public boolean killTask(TaskAttemptID taskId, boolean shouldFail)
      throws IOException {
    return false;
  }

  public static BSPMaster constructMaster(
      Class<? extends BSPMaster> masterClass, final Configuration conf) {
    try {
      Constructor<? extends BSPMaster> c = masterClass
          .getConstructor(Configuration.class);
      return c.newInstance(conf);
    } catch (Exception e) {
      throw new RuntimeException("Failed construction of " + "Master: "
          + masterClass.toString()
          + ((e.getCause() != null) ? e.getCause().getMessage() : ""), e);
    }
  }

  public void shutdown() {
    this.interTrackerServer.stop();
  }

  TreeMap<String, String> taskidToTrackerMap = new TreeMap<String, String>();
  TreeMap<String, TreeSet<String>> trackerToTaskMap = new TreeMap<String, TreeSet<String>>();
  Map<String, TaskInProgress> taskidToTIPMap = new TreeMap<String, TaskInProgress>();

  public void createTaskEntry(String taskid, String groomServer,
      TaskInProgress taskInProgress) {
    LOG.info("Adding task '" + taskid + "' to tip " + taskInProgress.getTIPId()
        + ", for tracker '" + groomServer + "'");
  }

  public static void main(String[] args) {
    StringUtils.startupShutdownMessage(BSPMaster.class, args, LOG);
    if (args.length != 0) {
      System.out.println("usage: HamaMaster");
      System.exit(-1);
    }

    try {
      HamaConfiguration conf = new HamaConfiguration();
      BSPMaster master = startMaster(conf);
      master.offerService();
    } catch (Throwable e) {
      LOG.fatal(StringUtils.stringifyException(e));
      System.exit(-1);
    }
  }
}
