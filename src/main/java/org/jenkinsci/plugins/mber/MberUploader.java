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
import com.mber.client.BuildStatus;
import com.mber.client.MberClient;
import com.mber.client.MberJSON;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;

public class MberUploader extends Builder
{
  public static String getDefaultArtifactFolder()
  {
    return "build/jenkins/${JOB_NAME}/${BUILD_NUMBER}";
  }

  public static String getDefaultArtifactTags()
  {
    return "${JOB_NAME} ${BUILD_NUMBER}";
  }

  private final String accessProfileName;
  private final String buildArtifacts;
  private final String artifactFolder;
  private final String artifactTags;
  private final boolean overwriteExistingFiles;
  private final boolean linkToLocalFiles;
  private final boolean showProgress;
  private final boolean optional;
  private final int attempts;

  @DataBoundConstructor
  public MberUploader(String accessProfileName, String buildArtifacts, String artifactFolder, String artifactTags, boolean overwriteExistingFiles, boolean linkToLocalFiles, boolean showProgress, boolean optional, int attempts)
  {
    this.accessProfileName = accessProfileName;
    this.buildArtifacts = buildArtifacts;
    this.artifactFolder = artifactFolder;
    this.artifactTags = artifactTags;
    this.overwriteExistingFiles = overwriteExistingFiles;
    this.linkToLocalFiles = linkToLocalFiles;
    this.showProgress = showProgress;
    this.optional = optional;
    this.attempts = attempts;
  }

  public String getAccessProfileName()
  {
    return this.accessProfileName;
  }

  public String getBuildArtifacts()
  {
    return this.buildArtifacts;
  }

  public String getArtifactFolder()
  {
    // If the input box is cleared, reset the folder to its default value.
    if (this.artifactFolder == null || this.artifactFolder.isEmpty()) {
      return getDefaultArtifactFolder();
    }
    return this.artifactFolder;
  }

  public String getArtifactTags()
  {
    // If the input box is cleared, reset the tags to their default values.
    if (this.artifactTags == null || this.artifactTags.isEmpty()) {
      return getDefaultArtifactTags();
    }
    return this.artifactTags;
  }

  public int getAttempts()
  {
    return this.attempts;
  }

  public boolean isOverwriteExistingFiles()
  {
    return this.overwriteExistingFiles;
  }

  public boolean isLinkToLocalFiles()
  {
    return this.linkToLocalFiles;
  }

  public boolean isShowProgress()
  {
    return this.showProgress;
  }

  public boolean isOptional()
  {
    return this.optional;
  }

  @Override
  public boolean perform(final AbstractBuild build, final Launcher launcher, final BuildListener listener)
  {
    // Users can set custom workspaces and inadvertantly invalidate the build's workspace.
    final FilePath workspace = build.getWorkspace();
    if (workspace == null) {
      log(listener, "Files will not be uploaded because a workspace for this build was not found.");
      return isOptional();
    }

    // Resolve environment variables in upload tags.
    final List<String> fileTags = resolveFileTags(build, listener);
    if (fileTags == null) {
      return isOptional();
    }

    // Resolve environment variables in the upload folder.
    final String uploadFolder = resolveUploadFolder(build, listener);
    if (uploadFolder == null) {
      return isOptional();
    }

    // Resolve environment variables in files being uploaded.
    final String[] fileIdentifiers = resolveFileIdentifiers(build, listener);
    if (fileIdentifiers == null) {
      return isOptional();
    }

    ArrayList<FilePath> uploadableFiles = new ArrayList<FilePath>();
    if (isLinkToLocalFiles()) {
      // We're linking to local files, so we can assume these are complete paths.
      for (final String glob : fileIdentifiers) {
        uploadableFiles.add(new FilePath(new File(glob)));
      }
    }
    else {
      // Files are specified as paths relative to the workspace.
      for (final String glob : fileIdentifiers) {
        try {
          final FilePath[] files = workspace.list(glob);
          uploadableFiles.addAll(Arrays.asList(files));
        }
        catch (final Exception e) {
          log(listener, "Failed to access local files matching %s. None will be uploaded.", glob);
          log(listener, e.getLocalizedMessage());
          return isOptional();
        }
      }
    }

    if (uploadableFiles.isEmpty()) {
      log(listener, "No uploadable files where found. Check that you spelled the file names correctly.");
      return isOptional();
    }

    // Set up a Mber client and log in so we get an access token.
    final MberClient mber = new Retryable<MberClient>(listener.getLogger(), getAttempts()) {
      @Override
      public MberClient call()
      {
        // The global configuration may be modified while the job's running.
        final MberAccessProfile accessProfile = MberNotifier.getAccessProfile(getAccessProfileName());
        if (accessProfile == null) {
          throw new RetryException(String.format("An access profile named '%s' was not found. Make sure it hasn't been deleted from the global configuration.", getAccessProfileName()));
        }

        // The access profile may have invalid credentials if it's being modified.
        final MberClient mber = makeMberClient(listener, accessProfile);
        log(listener, "Connecting to Mber at "+mber.getURL());
        final JSONObject response = mber.login(accessProfile.getUsername(), accessProfile.getPassword().getPlainText());
        if (!MberJSON.isSuccess(response)) {
          throw new RetryException("Failed to connect to Mber. Check your configuration settings.");
        }

        return mber;
      }
    }.run();

    // Uploads require access tokens, so bail if we didn't get one.
    if (mber == null) {
      log(listener, "Failed to connect to Mber. Check your configuration settings.");
      return isOptional();
    }

    // If there's a Mber build associated with this Jenkins build, we can set
    // upload directories on the Mber build.
    if (build.getProject().getPublishersList().get(MberNotifier.class) != null) {

      // Try to find the Mber project associated with this Jenkins job. The
      // notifier should have created this already.
      final String projectName = build.getProject().getDisplayName();
      final String projectDescription = build.getProject().getDescription();
      log(listener, "Finding Mber project %s", projectName);

      // Duplicate responses from create look up the project by name and return
      // the ID if the project was found. So it's safe to use this as a "find".
      JSONObject response = new Retryable<JSONObject>(listener.getLogger(), getAttempts()) {
        @Override
        public JSONObject call() {
          final JSONObject response = mber.mkproject(projectName, projectDescription);
          if (MberJSON.isFailed(response)) {
            final String error = MberJSON.getString(response, "error");
            throw new RetryException(String.format("Failed to find Mber project named %s. %s", projectName, error));
          }
          return response;
        }
      }.run();

      // Bail since we couldn't find or create a Mber project associated with
      // this Jenkins job.
      if (!MberJSON.isSuccess(response)) {
        log(listener, "Failed to find Mber project named %s", projectName);
        log(listener, MberJSON.getString(response, "error"));
        return isOptional();
      }

      // Try to find the Mber build associated with this Jenkins build. The
      // notifier should have created this already. We're using default values.
      // The notifier will handle updating this to the correct values when the
      // job's done.
      final String buildAlias = build.getUrl();
      final String buildName = build.getDisplayName();
      final String buildDescription = build.getDescription();
      log(listener, "Finding Mber build with alias %s", buildAlias);

      // Duplicate responses from create look up the build by alias and return
      // the ID if it was found. So it's safe to use this as a "find".
      response = new Retryable<JSONObject>(listener.getLogger(), getAttempts()) {
        @Override
        public JSONObject call() {
          final JSONObject response = mber.mkbuild(buildName, buildDescription, buildAlias, BuildStatus.RUNNING);
          if (MberJSON.isFailed(response)) {
            final String error = MberJSON.getString(response, "error");
            throw new RetryException(String.format("Failed to find Mber build with alias %s. %s", buildAlias, error));
          }
          return response;
        }
      }.run();

      // Bail since we couldn't find or create a Mber build associated with
      // this Jenkins build.
      if (!MberJSON.isSuccess(response)) {
        log(listener, "Failed to find Mber build with alias named %s", buildAlias);
        log(listener, MberJSON.getString(response, "error"));
        return isOptional();
      }
    }

    // Upload files one at a time. Each file retries individually if it fails.
    for (final FilePath file : uploadableFiles) {
      log(listener, "Uploading file %s", file.getRemote());

      // Append the file's name to the list of tags.
      ArrayList<String> tagList = new ArrayList<String>(fileTags);
      tagList.add(file.getName());
      final String[] tags = tagList.toArray(new String[tagList.size()]);

      // Create the folder in Mber Drive where the file will be uploaded.
      final String folder = resolveUploadDirectory(uploadFolder, workspace, file);
      JSONObject response = new Retryable<JSONObject>(listener.getLogger(), getAttempts()) {
        @Override
        public JSONObject call()
        {
          final JSONObject response = mber.mkpath(folder);
          if (MberJSON.isFailed(response)) {
            final String error = MberJSON.getString(response, "error");
            throw new RetryException(String.format("Failed to create Mber folder %s. %s", folder, error));
          }
          return response;
        }
      }.run();

      // Bail if we couldn't create a folder to upload builds into.
      final String directoryId = MberJSON.getString(response, "directoryId");
      if (directoryId == null || directoryId.isEmpty()) {
        log(listener, "Failed to create Mber folder %s", folder);
        log(listener, MberJSON.getString(response, "error"));
        return isOptional();
      }

      // Associate the directory with the build in Mber.
      if (build.getProject().getPublishersList().get(MberNotifier.class) != null) {
        response = new Retryable<JSONObject>(listener.getLogger(), getAttempts()) {
          @Override
          public JSONObject call()
          {
            final JSONObject response = mber.setBuildDirectory(directoryId);
            if (MberJSON.isFailed(response)) {
              final String error = MberJSON.getString(response, "error");
              throw new RetryException(String.format("Failed to update Mber build with folder %s. %s", folder, error));
            }
            return response;
          }
        }.run();

        // Bail if we couldn't associate the folder with a build in Mber.
        if (!MberJSON.isSuccess(response)) {
          log(listener, "Failed to update Mber build with folder %s", folder);
          log(listener, MberJSON.getString(response, "error"));
          return isOptional();
        }
      }

      // Upload the file to Mber, retrying as necessary.
      response = new Retryable<JSONObject>(listener.getLogger(), getAttempts()) {
        @Override
        public JSONObject call()
        {
          JSONObject response;
          if (isLinkToLocalFiles()) {
            response = mber.link(file, directoryId, file.getName(), tags, isOverwriteExistingFiles());
          }
          else {
            response = mber.upload(file, directoryId, file.getName(), tags, isOverwriteExistingFiles(), isShowProgress());  
          }
          if (!MberJSON.isSuccess(response) && !MberJSON.isAborted(response)) {
            throw new RetryException(response.getString("error"));
          }
          return response;
        }
      }.run();

      // The upload's already been retried, so bail if it's not successful.
      if (!MberJSON.isSuccess(response)) {
        log(listener, "Failed to upload file %s", file);
        log(listener, response.getString("error"));
        return isOptional();
      }
    }

    return true;
  }

  // Resolves any environment variables (like $BUILD_NUMBER) that might be in the tags list.
  private List<String> resolveFileTags(final AbstractBuild build, final BuildListener listener)
  {
    try {
      ArrayList<String> tags = new ArrayList<String>();
      // Resolve then split. This allows users to put tags in environment variables.
      final String[] userTags = build.getEnvironment(listener).expand(getArtifactTags()).split("\\s+");
      for (final String tag : userTags) {
        if (tag != null && !tag.isEmpty()) {
          tags.add(tag);
        }
      }
      return tags;
    }
    catch (final Exception e) {
      log(listener, "Failed to resolve environment variables in tags list.");
      log(listener, e.getLocalizedMessage());
      return null;
    }
  }

  // Resolves any environment variables (like $BUILD_NUMBER) that might be in the files list.
  private String[] resolveFileIdentifiers(final AbstractBuild build, final BuildListener listener)
  {
    try {
      // Resolve then split. This allows users to put files in environment variables.
      return build.getEnvironment(listener).expand(getBuildArtifacts()).split("\\s+");
    }
    catch (final Exception e) {
      log(listener, "Failed to resolve environment variables in files list.");
      log(listener, e.getLocalizedMessage());
      return null;
    }
  }

  // Resolves any environment variables (like $BUILD_NUMBER) that might be in the upload folder.
  private String resolveUploadFolder(final AbstractBuild build, final BuildListener listener)
  {
    try {
      // Resolve then split. This allows users to put upload folders in environment variables.
      return build.getEnvironment(listener).expand(getArtifactFolder());
    }
    catch (final Exception e) {
      log(listener, "Failed to resolve environment variables in the destination folder.");
      log(listener, e.getLocalizedMessage());
      return null;
    }
  }

  // Resolves the upload directory in Mber for the given file assuming it's in the provided base path.
  private String resolveUploadDirectory(String basePath, final FilePath workspace, final FilePath file)
  {
    final File base = new File(basePath);
    final String ws = workspace.getRemote();
    String name = (new File(base, File.pathSeparator)).getPath();
    // Resolve upload paths so they're relative to the workspace.
    if (!isLinkToLocalFiles()) {
      name = file.getRemote().replace(ws, "");
      name = (new File(base, name)).getPath();
    }
    name = name.replace("\\", "/");
    name = name.substring(0, name.lastIndexOf("/"));
    return name;
  }

  private MberClient makeMberClient(final BuildListener listener, final MberAccessProfile accessProfile)
  {
    final MberClient mber = new MberClient(accessProfile.getUrl(), accessProfile.getApplication());
    mber.setListener(listener);
    return mber;
  }

  private void log(final BuildListener listener, final String message, final Object... args)
  {
    if (!message.isEmpty()) {
      listener.getLogger().println(String.format(message, args));
    }
  }

  @Override
  public MberUploader.DescriptorImpl getDescriptor() {
    return (MberUploader.DescriptorImpl)super.getDescriptor();
  }

  @Extension
  public static final class DescriptorImpl extends BuildStepDescriptor<Builder>
  {
    @Override
    public String getDisplayName()
    {
      return "Upload files to Mber";
    }

    @Override
    public boolean isApplicable(Class<? extends AbstractProject> aClass)
    {
      return true;
    }

    // Called when the Mber access profile selector for the build step is populated.
    public ListBoxModel doFillAccessProfileNameItems() {
      return MberNotifier.getAccessProfileNameItems();
    }

    // Pass through method for getting the default upload folder into the jelly config.
    public String getDefaultArtifactFolder()
    {
      return MberUploader.getDefaultArtifactFolder();
    }

    // Pass through method for getting the default tags into the jelly config.
    public String getDefaultArtifactTags()
    {
      return MberUploader.getDefaultArtifactTags();
    }
  }
}