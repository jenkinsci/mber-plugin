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
import com.mber.client.MberJSON;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

public class MberDownloader extends Builder
{
  private final String accessProfileName;
  private final String files;
  private final boolean overwriteExistingFiles;
  private final boolean useTags;
  private final boolean showProgress;
  private final boolean optional;

  @DataBoundConstructor
  public MberDownloader(String accessProfileName, String files, boolean overwriteExistingFiles, boolean useTags, boolean showProgress, boolean optional)
  {
    this.accessProfileName = accessProfileName;
    this.files = files;
    this.overwriteExistingFiles = overwriteExistingFiles;
    this.useTags = useTags;
    this.showProgress = showProgress;
    this.optional = optional;
  }

  public String getAccessProfileName()
  {
    return this.accessProfileName;
  }

  public String getFiles()
  {
    return this.files;
  }

  public boolean isOverwriteExistingFiles()
  {
    return this.overwriteExistingFiles;
  }

  public boolean isUseTags()
  {
    return this.useTags;
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
      log(listener, "Files will not be downloaded because a workspace for this build was not found.");
      return isOptional();
    }

    // Set up an Mber client and log in so we get an access token.
    final MberClient mber = new Retryable<MberClient>(listener.getLogger(), 5) {
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
          throw new RetryException(String.format("Failed to connect to Mber. Check your configuration settings.", getAccessProfileName()));
        }

        return mber;
      }
    }.run();

    // Downloads require access tokens, so bail if we didn't get one.
    if (mber == null) {
      return isOptional();
    }

    // Resolve environment variables in file identifiers.
    final String[] fileIdentifiers = resolveFileIdentifiers(build, listener);
    if (fileIdentifiers == null) {
      return isOptional();
    }

    ArrayList<JSONObject> documents = new ArrayList<JSONObject>();
    if (!isUseTags()) {
      // Look for files with matching IDs.
      for (final String documentId : fileIdentifiers) {
        final JSONObject response = new Retryable<JSONObject>(listener.getLogger(), 5) {
          @Override
          public JSONObject call()
          {
            final JSONObject result = mber.readDocument(documentId);
            // Only retry failures. Semi-successful responses like NotFound, won't retry.
            if (MberJSON.isFailed(result)) {
              throw new RetryException(MberJSON.getString(result, "error"));
            }
            return result;
          }
        }.run();

        // Let the user know we couldn't find their file. The Retryable handles exceptions e.g. network connectivity issues.
        if (!MberJSON.isSuccess(response)) {
          log(listener, "A file with ID %s was not found in Mber. Check that you spelled the file identifier correctly.", documentId);
          return isOptional();
        }

        // Make sure the document is flagged as downloadable so we can use it in the download API.
        final JSONObject document = MberJSON.getObject(response, "result");
        if (!MberJSON.getBooleanOrFalse(document, "canDownload")) {
          log(listener, "The file with ID %s is not downloadable from Mber. Check that the file has been uploaded or synced to a CDN.", documentId);
          return isOptional();
        }

        documents.add(document);
      }
    }
    else {
      // Look for files with matching tags.
      final JSONObject response = new Retryable<JSONObject>(listener.getLogger(), 5) {
        @Override
        public JSONObject call()
        {
          final JSONObject result = mber.findDocumentsWithTags(fileIdentifiers);
          // Only retry failures. Semi-successful responses like NotFound, won't retry.
          if (MberJSON.isFailed(result)) {
            throw new RetryException(MberJSON.getString(result, "error"));
          }
          return result;
        }
      }.run();

      // Let the user know we couldn't find any files. The Retryable handles exceptions e.g. network connectivity issues.
      if (!MberJSON.isSuccess(response) || MberJSON.getArray(response, "results").isEmpty()) {
        log(listener, "Failed to find files with tags: %s", StringUtils.join(fileIdentifiers, ", "));
        return isOptional();
      }

      // Make sure all the found documents as downloadable so we can use them in the download API.
      final JSONArray results = MberJSON.getArray(response, "results");
      final Iterator<JSONObject> itr = results.iterator();
      while (itr.hasNext()) {
        final JSONObject document = itr.next();
        if (!MberJSON.getBooleanOrFalse(document, "canDownload")) {
          final String documentId = document.getString("documentId");
          log(listener, "The file with ID %s is not downloadable from Mber. Check that the file has been uploaded or synced to a CDN.", documentId);
          return isOptional();
        }
        documents.add(document);
      }
    }

    // The user specified files to download but we didn't find any in Mber.
    if (fileIdentifiers.length > 0 && documents.isEmpty()) {
      log(listener, "No dowloadable files where found in Mber. Check that you spelled the file identifiers correctly.");
      return isOptional();
    }

    for (final JSONObject document : documents) {
      final String name = document.getString("name");
      final FilePath file = workspace.child(name);

      // Check for existing files before downloading new ones that overwrite them.
      try {
        if (!isOverwriteExistingFiles() && file.exists()) {
          log(listener, "A file named %s already exists in the workspace.", name);
          return isOptional();
        }
      }
      catch (final InterruptedException e) {
        log(listener, e.getLocalizedMessage());
        return isOptional();
      }
      catch (final IOException e) {
        log(listener, e.getLocalizedMessage());
        return isOptional();
      }

      // Download the file from Mber, retrying as necessary.
      final String documentId = document.getString("documentId");
      log(listener, "Dowloading file %s", name);
      final JSONObject response = new Retryable<JSONObject>(listener.getLogger(), 5) {
        @Override
        public JSONObject call()
        {
          final JSONObject response = mber.download(file, documentId, isShowProgress());
          if (!MberJSON.isSuccess(response)) {
            throw new RetryException(response.getString("error"));
          }
          return response;
        }
      }.run();

      // The download's already been retried, so bail if it's not successful.
      if (!MberJSON.isSuccess(response)) {
        return isOptional();
      }
    }

    return true;
  }

  // Resolves any environment variables (like $BUILD_NUMBER) that might be in the file identifier list.
  private String[] resolveFileIdentifiers(final AbstractBuild build, final BuildListener listener)
  {
    try {
      // Resolve then split. This allows users to put file lists in environment variables.
      return build.getEnvironment(listener).expand(getFiles()).split("\\s+");
    }
    catch (final Exception e) {
      log(listener, "Failed to resolve environment variables in file identifiers.");
      log(listener, e.getLocalizedMessage());
      return null;
    }
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
  public DescriptorImpl getDescriptor() {
    return (DescriptorImpl)super.getDescriptor();
  }

  @Extension
  public static final class DescriptorImpl extends BuildStepDescriptor<Builder>
  {
    @Override
    public String getDisplayName()
    {
      return "Download files from Mber";
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
  }
}
