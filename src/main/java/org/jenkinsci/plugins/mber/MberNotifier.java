/*
The Jenkins Mber Plugin is free software distributed under the terms of the MIT
license (http://opensource.org/licenses/mit-license.html) reproduced here:

Copyright (c) 2013-2015 Mber

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*/

package org.jenkinsci.plugins.mber;
import com.mber.client.MberClient;
import com.mber.client.BuildStatus;
import com.mber.client.HTTParty;
import com.mber.client.MberJSON;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.HashMap;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

public class MberNotifier extends Notifier
{
  private final String buildName;
  private final String buildDescription;
  private final boolean uploadConsoleLog;
  private final boolean uploadTestResults;
  private JSONObject mberConfig;
  private Map<String, List<HTTParty.Call>> callHistory;

  // Version 1.3 profiles named Mber access profiles.
  private String accessProfileName;

  // Version 1.1 supports multiple upload destinations.
  private boolean uploadArtifacts;
  private List<UploadArtifactsBlock> uploadDestinations;

  // Version 1.2 tied the Mber credentials to the Jenkins job. These are transient so they're not saved.
  transient private String application;
  transient private String username;
  transient private Secret password;

  // Version 1.0 only supported one upload destination. These are transient so they're not saved.
  transient private boolean overwriteExistingFiles = false;
  transient private String buildArtifacts;
  transient private String artifactFolder;
  transient private String artifactTags;

  @DataBoundConstructor
  public MberNotifier(String accessProfileName, String buildName, String buildDescription, boolean uploadTestResults, boolean uploadConsoleLog, UploadArtifactsFlag uploadArtifacts)
  {
    this.accessProfileName = accessProfileName;
    this.buildName = buildName;
    this.buildDescription = buildDescription;
    this.uploadConsoleLog = uploadConsoleLog;
    this.uploadTestResults = uploadTestResults;
    this.uploadArtifacts = (uploadArtifacts != null);
    if (uploadArtifacts != null) {
      this.uploadDestinations = uploadArtifacts.getUploadDestinations();
    } else {
      this.uploadDestinations = new ArrayList<UploadArtifactsBlock>();
    }
  }

  // This is called when old configurations are loaded and need to be translated
  // into new formats. Do any kind of data migration here and return the fixed
  // object. Remember to mark old private instance variables transient to ensure
  // they're not saved.
  public Object readResolve()
  {
    // Version 1.0 didn't support multiple upload destinations. Map them to the 1.1 format if they're defined.
    if (this.buildArtifacts != null || this.artifactFolder != null || this.artifactTags != null) {
      this.uploadArtifacts = true;
      this.uploadDestinations = new ArrayList<UploadArtifactsBlock>();
      this.uploadDestinations.add(new UploadArtifactsBlock(this.buildArtifacts, this.artifactFolder, this.artifactTags, this.overwriteExistingFiles, false));
    }
    // Version 1.2 tied Mber credentials to a Jenkins job. Map them to the 1.3 format with named access profiles if they're defined.
    if (this.application != null || this.username != null || this.password != null) {
      // We don't have access to the Jenkins job at this point, so use a combination of the application and username for the access profile name.
      this.accessProfileName = String.format("%s.%s", this.application, this.username);
      final String url = getDescriptor().resolveMberUrlForNotification();
      final MberAccessProfile accessProfile = new MberAccessProfile(this.accessProfileName, this.application, this.username, this.password.getPlainText(), url);
      getDescriptor().setOrAddAccessProfile(accessProfile);
    }
    return this;
  }

  public String getAccessProfileName()
  {
    return this.accessProfileName;
  }

  public String getApplication()
  {
    final MberAccessProfile profile = getDescriptor().getAccessProfile(getAccessProfileName());
    if (profile != null) {
      return profile.getApplication();
    }
    return null;
  }

  public String getUsername()
  {
    final MberAccessProfile profile = getDescriptor().getAccessProfile(getAccessProfileName());
    if (profile != null) {
      return profile.getUsername();
    }
    return null;
  }

  // Providing public access to encrypted passwords allows tests to verify
  // configuration save and load functionality without exposing plain text
  // passwords in test result logs.
  public String getPassword()
  {
    final MberAccessProfile profile = getDescriptor().getAccessProfile(getAccessProfileName());
    if (profile != null) {
      return profile.getPassword().getEncryptedValue();
    }
    return null;
  }

  private String getDecryptedPassword()
  {
    final MberAccessProfile profile = getDescriptor().getAccessProfile(getAccessProfileName());
    if (profile != null) {
      return profile.getPassword().getPlainText();
    }
    return null;
  }

  private String getMberUrl()
  {
    final MberAccessProfile profile = getDescriptor().getAccessProfile(getAccessProfileName());
    if (profile != null) {
      return profile.getUrl();
    }
    return null;
  }

  public String getBuildName()
  {
    return buildName;
  }

  private String getMberBuildName(final AbstractBuild build, final BuildListener listener)
  {
    if (buildName != null && !buildName.isEmpty()) {
      return this.resolveEnvironmentVariables(build, listener, buildName);
    }
    return build.getDisplayName();
  }

  public String getBuildDescription()
  {
    return buildDescription;
  }

  private String getMberBuildDescription(final AbstractBuild build, final BuildListener listener)
  {
    if (buildDescription != null && !buildDescription.isEmpty()) {
      return this.resolveEnvironmentVariables(build, listener, buildDescription);
    }
    return build.getDescription();
  }

  public boolean isUploadConsoleLog()
  {
    return uploadConsoleLog;
  }

  public boolean isUploadTestResults()
  {
    return uploadTestResults;
  }

  public boolean isUploadArtifacts()
  {
    return uploadArtifacts;
  }

  public boolean isOverwriteExistingFiles(final int index)
  {
    final List<UploadArtifactsBlock> uploads = getUploadDestinations();
    if (index < 0 || index >= uploads.size()) {
      return false;
    }
    return uploads.get(index).isOverwriteExistingFiles();
  }

  public boolean isLinkToLocalFiles(final int index)
  {
    final List<UploadArtifactsBlock> uploads = getUploadDestinations();
    if (index < 0 || index >= uploads.size()) {
      return false;
    }
    return uploads.get(index).isLinkToLocalFiles();
  }

  public String getBuildArtifacts(final int index)
  {
    final List<UploadArtifactsBlock> uploads = getUploadDestinations();
    if (index < 0 || index >= uploads.size()) {
      return null;
    }
    return uploads.get(index).getBuildArtifacts();
  }

  public String getArtifactFolder(final int index)
  {
    final List<UploadArtifactsBlock> uploads = getUploadDestinations();
    if (index < 0 || index >= uploads.size()) {
      return UploadArtifactsBlock.getDefaultArtifactFolder();
    }
    return uploads.get(index).getArtifactFolder();
  }

  public String getArtifactTags(final int index)
  {
    final List<UploadArtifactsBlock> uploads = getUploadDestinations();
    if (index < 0 || index >= uploads.size()) {
      return UploadArtifactsBlock.getDefaultArtifactTags();
    }
    return uploads.get(index).getArtifactTags();
  }

  public List<UploadArtifactsBlock> getUploadDestinations()
  {
    return uploadDestinations;
  }

  private void recordCallHistory(final AbstractBuild build, final MberClient mber) {
    if (this.callHistory == null) {
      this.callHistory = new HashMap();
    }
    String buildId = getCallHistoryId(build);
    List<HTTParty.Call> calls = getCallHistory(build);
    calls.addAll(mber.getCallHistory());
    this.callHistory.put(buildId, calls);
  }

  List<HTTParty.Call> getCallHistory(final AbstractBuild build) {
    String buildId = getCallHistoryId(build);
    if (this.callHistory != null && this.callHistory.containsKey(buildId)) {
      return this.callHistory.get(buildId);
    }
    return new ArrayList();
  }

  private void clearCallHistory(final AbstractBuild build) {
    if (this.callHistory != null) {
      String buildId = getCallHistoryId(build);
      this.callHistory.remove(buildId);
    }
  }

  private String getCallHistoryId(final AbstractBuild build) {
    // Use the URL to the build as a unique ID, since it's guaranteed to be unique,
    // unlike the getId() function which just returns the time the build started.
    return build.getUrl();
  }

  private String[] getUploadTags(final AbstractBuild build, final BuildListener listener, final FilePath file, final int index)
  {
    return getUploadTags(build, listener, file.getName(), index);
  }

  private String[] getUploadTags(final AbstractBuild build, final BuildListener listener, final String name, final int index)
  {
    ArrayList<String> tags = new ArrayList<String>();
    tags.add(name);

    String[] userTags = getArtifactTags(index).split("\\s+");
    for (String tag : userTags) {
      String resolvedTag = this.resolveEnvironmentVariables(build, listener, tag);
      if (resolvedTag != null && !resolvedTag.isEmpty()) {
        tags.add(resolvedTag);
      }
    }

    return tags.toArray(new String[tags.size()]);
  }

  public void uploadLogFile(AbstractBuild build, BuildListener listener, final MberClient mber)
  {
    if (!isUploadConsoleLog()) {
      return;
    }

    File logFile = build.getLogFile();
    if (logFile == null || !logFile.exists() || logFile.length() <= 0) {
      return;
    }

    log(listener, "Uploading console output to Mber");
    // Console logs go into the first available artifact upload block, hence the 0 index.
    // If no upload block is defined, they'll go into the default upload folder.
    JSONObject response = makeArtifactFolder(build, listener, mber, false, 0);
    if (!response.getString("status").equals("Success")) {
      log(listener, response.getString("error"));
      return;
    }

    String uploadDirectoryId = response.getString("directoryId");
    String[] tags = getUploadTags(build, listener, "console.log", 0);

    response = mber.upload(new FilePath(logFile), uploadDirectoryId, "console.log", tags, false, true);
    if (response.getString("status").equals("Duplicate")) {
      log(listener, "You already have a build artifact named \"console.log\". Please rename your build artifact.");
      return;
    }
    if (!response.getString("status").equals("Success")) {
      log(listener, response.getString("error"));
    }
  }

  private void writeCallHistory(final AbstractBuild build, final BuildListener listener, final MberClient mber)
  {
    // Only write debug information if the build failed.
    if (build.getResult().equals(Result.FAILURE)) {
      // Aggregate the call history for both prebuild and perform.
      recordCallHistory(build, mber);
      log(listener, "The following calls were made to Mber:");
      for (HTTParty.Call call : getCallHistory(build)) {
        // Don't show query strings as part of the URI. They can contain sensitive data like tokens.
        String url = call.uri.toString();
        int offset = url.indexOf('?');
        if (offset >= 0) {
          url = url.substring(0, offset);
        }
        log(listener, call.method+" "+url+" - "+call.code);
      }
    }
    // Clear the call history for this build so memory usage doesn't keep growing.
    clearCallHistory(build);
  }

  private JSONObject makeArtifactFolder(AbstractBuild build, BuildListener listener, final MberClient mber, boolean logging, final int index)
  {
    String resolvedArtifactFolder = resolveArtifactFolder(build, listener, index);
    if (resolvedArtifactFolder == null || resolvedArtifactFolder.isEmpty()) {
      return MberJSON.failed("Couldn't resolve environment variables in artifact folder "+getArtifactFolder(index));
    }

    if (logging) {
      log(listener, "Creating artifact folder "+resolvedArtifactFolder);
    }

    JSONObject response = mber.mkpath(resolvedArtifactFolder);
    if (!response.getString("status").equals("Success")) {
      return response;
    }

    String uploadDirectoryId = response.getString("directoryId");
    response = mber.setBuildDirectory(uploadDirectoryId);
    if (!response.getString("status").equals("Success")) {
      return response;
    }

    JSONObject success = MberJSON.success();
    success.put("directoryId", uploadDirectoryId);
    return success;
  }

  private String resolveArtifactFolder(AbstractBuild build, BuildListener listener, final int index)
  {
    return resolveEnvironmentVariables(build, listener, getArtifactFolder(index));
  }

  private FilePath[] findBuildArtifacts(final AbstractBuild build, final BuildListener listener, final int index)
  {
    String artifactGlob = resolveEnvironmentVariables(build, listener, getBuildArtifacts(index));
    if (artifactGlob == null || artifactGlob.isEmpty()) {
      return new FilePath[0];
    }

    // Version 1.2 supports optionally linking artifacts instead of uploading them. Skip resolving
    // links as if they're relative to the workspace, since we can't know if they're absolute or not.
    if (isLinkToLocalFiles(index)) {
      ArrayList<FilePath> links = new ArrayList<FilePath>();
      String[] globs = artifactGlob.split("\\s+");
      for (String glob : globs) {
        links.add(new FilePath(new File(glob)));
      }
      return links.toArray(new FilePath[0]);
    }

    // Versions prior to 1.2 only support uploading files relative to the workspace.
    try {
      ArrayList<FilePath> artifacts = new ArrayList<FilePath>();
      String[] globs = artifactGlob.split("\\s+");
      for (String glob : globs) {
        FilePath[] files = build.getWorkspace().list(glob);
        artifacts.addAll(Arrays.asList(files));
      }
      return artifacts.toArray(new FilePath[0]);
    }
    catch (Exception e) {
      return new FilePath[0];
    }
  }

  private Map<FilePath, String> findBuildArtifactFolders(final AbstractBuild build, final BuildListener listener, final FilePath[] artifacts, final int index)
  {
    // Version 1.2 supports optionally linking artifacts instead of uploading them.
    final boolean isLink = isLinkToLocalFiles(index);

    // Artifact paths are relative to the workspace, and keep their folder structure when uploaded to Mber.
    // Since the slave and master might be running on different OSes, we normalize the folder name to slashes.
    // Links upload directly to the base artifact folder since their folder structure is unkown.
    File base = new File(resolveArtifactFolder(build, listener, index));
    String workspace = build.getWorkspace().getRemote();
    HashMap namedArtifacts = new HashMap();
    for (FilePath path : artifacts) {
      if (path != null) {
        String name = (new File(base, File.pathSeparator)).getPath();
        if (!isLink) {
          name = path.getRemote().replace(workspace, "");
          name = (new File(base, name)).getPath();
        }
        name = name.replace("\\", "/");
        name = name.substring(0, name.lastIndexOf("/"));
        namedArtifacts.put(path, name);
      }
    }
    return namedArtifacts;
  }

  private String resolveEnvironmentVariables(final AbstractBuild build, final BuildListener listener, final String value)
  {
    try {
      return build.getEnvironment(listener).expand(value);
    }
    catch (Exception e) {
      return null;
    }
  }

  private MberClient makeMberClient()
  {
    if (mberConfig == null) {
      return new MberClient(getMberUrl(), getApplication());
    }
    return new MberClient(this.mberConfig);
  }

  private void log(final BuildListener listener, final String message)
  {
    listener.getLogger().println(message);
  }

  private boolean fail(final AbstractBuild build, final BuildListener listener, final MberClient mber, final String message)
  {
    log(listener, message);
    build.setResult(Result.FAILURE);
    return done(build, listener, mber);
  }

  private boolean isFailedBuild(final AbstractBuild build)
  {
    return !build.getResult().equals(Result.SUCCESS);
  }

  private boolean done(final AbstractBuild build, final BuildListener listener, final MberClient mber)
  {
    JSONObject result;
    // Refetch the build name and description, since users might have bound them to environment variables.
    String mberBuildName = getMberBuildName(build, listener);
    String mberBuildDescription = getMberBuildDescription(build, listener);
    if (isFailedBuild(build)) {
      log(listener, "Setting Mber build status to "+BuildStatus.COMPLETED.toString()+" "+BuildStatus.FAILURE.toString());
      result = mber.updateBuild(mberBuildName, mberBuildDescription, BuildStatus.COMPLETED, BuildStatus.FAILURE);
    }
    else {
      log(listener, "Setting Mber build status to "+BuildStatus.COMPLETED.toString()+" "+BuildStatus.SUCCESS.toString());
      result = mber.updateBuild(mberBuildName, mberBuildDescription, BuildStatus.COMPLETED, BuildStatus.SUCCESS);
    }
    if (!result.getString("status").equals("Success")) {
      // Don't call fail() here, otherwise we end up in a retry loop if we can't connect to Mber.
      log(listener, result.getString("error"));
      build.setResult(Result.FAILURE);
    }
    uploadTestEvents(build, listener, mber);
    uploadLogFile(build, listener, mber);
    writeCallHistory(build, listener, mber);
    return true;
  }

  private JSONObject downloadTestResults(AbstractBuild build, AbstractTestResultAction action)
  {
    try {
      String url = build.getAbsoluteUrl() + action.getUrlName() + "/api/json";
      String response = HTTParty.get(url).body;
      return (JSONObject)JSONSerializer.toJSON(response);
    }
    catch (IOException e) {
      return new JSONObject();
    }
  }

  private void uploadTestEvents(AbstractBuild build, BuildListener listener, final MberClient mber)
  {
    if (!isUploadTestResults()) {
      return;
    }

    AbstractTestResultAction testResultAction = build.getTestResultAction();
    if (testResultAction == null) {
      return;
    }

    log(listener, "Getting test results from Jenkins");

    JSONObject testResults = downloadTestResults(build, testResultAction);

    log(listener, "Uploading test results to Mber");
    // Test results go into the first available artifact upload block, hence the 0 index.
    // If no upload block is defined, they'll go into the default upload folder.
    JSONObject response = makeArtifactFolder(build, listener, mber, false, 0);
    if (!response.getString("status").equals("Success")) {
      log(listener, response.getString("error"));
      return;
    }

    String uploadDirectoryId = response.getString("directoryId");
    String[] tags = getUploadTags(build, listener, "tests.json", 0);

    response = mber.upload(testResults, uploadDirectoryId, "tests.json", tags);
    if (response.getString("status").equals("Duplicate")) {
      log(listener, "You already have a build artifact named \"tests.json\". Please rename your build artifact.");
      return;
    }
    if (!response.getString("status").equals("Success")) {
      log(listener, response.getString("error"));
    }

    response = mber.publishTestResults(testResults);
    if (!response.getString("status").equals("Success")) {
      log(listener, response.getString("error"));
    }
  }

  @Override
  public boolean prebuild(AbstractBuild build, BuildListener listener)
  {
    // Clear the old call history. The notifier persists on a per-job basis.
    clearCallHistory(build);
    // Clear the cached Mber config. It will have build IDs and like from previous runs.
    this.mberConfig = null;

    MberClient mber = makeMberClient();
    mber.setListener(listener);

    log(listener, "Connecting to Mber at "+mber.getURL());
    JSONObject response = mber.login(getUsername(), getDecryptedPassword());
    if (!response.getString("status").equals("Success")) {
      return fail(build, listener, mber, "Failed to connect to Mber. Check your configuration settings.");
    }

    log(listener, "Creating Mber project "+build.getProject().getDisplayName());
    response = mber.mkproject(build.getProject().getDisplayName(), build.getProject().getDescription());
    if (!response.getString("status").equals("Success")) {
      return fail(build, listener, mber, response.getString("error"));
    }

    String mberBuildName = getMberBuildName(build, listener);
    String mberBuildDescription = getMberBuildDescription(build, listener);
    log(listener, "Creating Mber build "+mberBuildName);
    log(listener, "Setting Mber build status to "+BuildStatus.RUNNING.toString());

    // Use the build's URL as the alias. The getId() function is not guaranteed to return a unique value.
    response = mber.mkbuild(mberBuildName, mberBuildDescription, build.getUrl(), BuildStatus.RUNNING);
    if (!response.getString("status").equals("Success")) {
      return fail(build, listener, mber, response.getString("error"));
    }

    this.mberConfig = mber.toJSON();
    recordCallHistory(build, mber);

    return true;
  }

  @Override
  public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener)
  {
    MberClient mber = makeMberClient();
    mber.setListener(listener);

    if (!isUploadArtifacts() || isFailedBuild(build)) {
      return done(build, listener, mber);
    }

    JSONArray errors = new JSONArray();

    for (int index = 0; index < getUploadDestinations().size(); ++index) {
      FilePath[] artifacts = findBuildArtifacts(build, listener, index);
      if (artifacts.length == 0) {
        errors.add("No build artifacts found in "+getBuildArtifacts(0));
      }

      JSONObject response = makeArtifactFolder(build, listener, mber, true, index);
      if (!response.getString("status").equals("Success")) {
        errors.add(response.getString("error"));
      }

      final boolean overwriteFiles = isOverwriteExistingFiles(index);
      final boolean isLink = isLinkToLocalFiles(index);

      Map<FilePath, String> buildArtifactFolders = findBuildArtifactFolders(build, listener, artifacts, index);
      Iterator<Map.Entry<FilePath, String>> folderItr = buildArtifactFolders.entrySet().iterator();
      while (folderItr.hasNext()) {
        Map.Entry<FilePath, String> artifact = folderItr.next();
        FilePath path = artifact.getKey();
        String folder = artifact.getValue();
        String[] tags = getUploadTags(build, listener, path, index);
        log(listener, "Uploading artifact "+path.getRemote());
        response = mber.mkpath(folder);
        String folderId = MberJSON.getString(response, "directoryId");
        if (!folderId.isEmpty()) {
          if (!isLink) {
            response = mber.upload(path, folderId, path.getName(), tags, overwriteFiles, true);
          } else {
            response = mber.link(path, folderId, path.getName(), tags, overwriteFiles);
          }
        }
        if (!response.getString("status").equals("Success")) {
          errors.add(MberJSON.getString(response, "error"));
        }
      }
    }

    if (!errors.isEmpty()) {
      return fail(build, listener, mber, errors.join("\n"));
    }

    return done(build, listener, mber);
  }

  @Override
  public boolean needsToRunAfterFinalized()
  {
    return false;
  }

  @Override
  public BuildStepMonitor getRequiredMonitorService()
  {
    return BuildStepMonitor.BUILD;
  }

  @Override
  public MberNotifier.DescriptorImpl getDescriptor()
  {
    return (MberNotifier.DescriptorImpl)super.getDescriptor();
  }

  @Extension
  public static final class DescriptorImpl extends BuildStepDescriptor<Publisher>
  {
    private List<MberAccessProfile> accessProfiles = new ArrayList<MberAccessProfile>();

    // Version 1.2 had a single global URL. Version 1.3 allows multiple access profiles.
    transient private String mberUrl;

    public DescriptorImpl()
    {
      super(MberNotifier.class);
      load();
    }

    // This is called when old configurations are loaded and need to be translated
    // into new formats. Do any kind of data migration here and return the fixed
    // object. Remember to mark old private instance variables transient to ensure
    // they're not saved.
    public Object readResolve()
    {
      // Version 1.2 had a single global URL. Version 1.3 allows multiple access profiles.
      // If we're coming from version 1.2 set up the default profile with the configured URL.
      if (this.mberUrl != null) {
        MberAccessProfile defaultProfile = new MberAccessProfile("default", "", "", "", this.mberUrl);
        this.accessProfiles.add(defaultProfile);
      }
      return this;
    }

    @Override
    public String getDisplayName()
    {
      return "Mber Notification";
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject formData) throws FormException
    {
      req.bindJSON(this, formData);
      save();
      return super.configure(req, formData);
    }

    @Override
    public boolean isApplicable(Class<? extends AbstractProject> aClass)
    {
      return true;
    }

    // Resolve the configured Mber URL when the configuration for a notification is read.
    public String resolveMberUrlForNotification()
    {
      // If this is a version 1.2 config, we'll have a mberUrl variable.
      if (this.mberUrl != null && !this.mberUrl.isEmpty()) {
        return this.mberUrl;
      }
      // If we've already resolved the global configuration for a 1.2 config, we'll have a default profile.
      final MberAccessProfile defaultProfile = getAccessProfile("default");
      if (defaultProfile != null) {
        final String url = defaultProfile.getUrl();
        if (url != null && !url.isEmpty()) {
          return url;
        }
      }
      // The user may have configured their own access profile. Use the first found profile with a valid URL.
      for (MberAccessProfile profile : getAccessProfiles()) {
        final String url = profile.getUrl();
        if (url != null && !url.isEmpty()) {
          return url;
        }
      }
      // There are no configured profiles, so use the default URL.
      return MberAccessProfile.getDefaultUrl();
    }

    public List<MberAccessProfile> getAccessProfiles()
    {
      return this.accessProfiles;
    }

    public void setAccessProfiles(final List<MberAccessProfile> accessProfiles)
    {
      this.accessProfiles = accessProfiles;
    }

    // Get an access profile by name. Returns null if a profile with the given name isn't found.
    public MberAccessProfile getAccessProfile(final String profileName)
    {
      for (MberAccessProfile profile : getAccessProfiles()) {
        if (profile.getName().equals(profileName)) {
          return profile;
        }
      }
      return null;
    }

    // Overwrites values in an existing access profile with the same name.
    // If no access profile with the same name is found, the new profile is added to the list.
    public void setOrAddAccessProfile(final MberAccessProfile accessProfile)
    {
      ListIterator<MberAccessProfile> profiles = getAccessProfiles().listIterator();
      while (profiles.hasNext()) {
        if (profiles.next().getName().equals(accessProfile.getName())) {
          profiles.set(accessProfile);
          return;
        }
      }
      profiles.add(accessProfile);
    }

    // Called when the Mber access profile selector for a Jenkins job is populated.
    public ListBoxModel doFillAccessProfileNameItems() {
      ListBoxModel model = new ListBoxModel();
      for (MberAccessProfile profile : getAccessProfiles()) {
        model.add(profile.getName(), profile.getName());
      }
      return model;
    }
  }

  // Pass through method for filling out an access profile name drop down.
  public static ListBoxModel getAccessProfileNameItems()
  {
    final MberNotifier.DescriptorImpl descriptor = (MberNotifier.DescriptorImpl)Jenkins.getInstance().getDescriptor(MberNotifier.class);
    return descriptor.doFillAccessProfileNameItems();
  }

  // Pass through method for reading an explicit acccess profile from the descriptor.
  public static MberAccessProfile getAccessProfile(final String profileName)
  {
    final MberNotifier.DescriptorImpl descriptor = (MberNotifier.DescriptorImpl)Jenkins.getInstance().getDescriptor(MberNotifier.class);
    return descriptor.getAccessProfile(profileName);
  }

  // Pass through method for adding a new access profile.
  public static void setOrAddAccessProfile(final MberAccessProfile accessProfile)
  {
    final MberNotifier.DescriptorImpl descriptor = (MberNotifier.DescriptorImpl)Jenkins.getInstance().getDescriptor(MberNotifier.class);
    descriptor.setOrAddAccessProfile(accessProfile);
  }
}
