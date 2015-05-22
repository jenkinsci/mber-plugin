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
import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

public class UploadArtifactsBlock implements Describable<UploadArtifactsBlock>
{
  public static String getDefaultArtifactFolder()
  {
    return "build/jenkins/${JOB_NAME}/${BUILD_NUMBER}";
  }

  public static String getDefaultArtifactTags()
  {
    return "${JOB_NAME} ${BUILD_NUMBER}";
  }

  private final String buildArtifacts;
  private final String artifactFolder;
  private final String artifactTags;
  private final boolean overwriteExistingFiles;

  // Version 1.2 supports linking to local files instead of uploading them.
  private boolean linkToLocalFiles = false;

  @DataBoundConstructor
  public UploadArtifactsBlock(String buildArtifacts, String artifactFolder, String artifactTags, boolean overwriteExistingFiles, boolean linkToLocalFiles)
  {
    this.buildArtifacts = buildArtifacts;
    this.artifactFolder = artifactFolder;
    this.artifactTags = artifactTags;
    this.overwriteExistingFiles = overwriteExistingFiles;
    this.linkToLocalFiles = linkToLocalFiles;
  }

  public String getBuildArtifacts()
  {
    return buildArtifacts;
  }

  public String getArtifactFolder()
  {
    if (artifactFolder == null || artifactFolder.isEmpty()) {
      return UploadArtifactsBlock.getDefaultArtifactFolder();
    }
    return artifactFolder;
  }

  public String getArtifactTags()
  {
    if (artifactTags == null || artifactTags.isEmpty()) {
      return UploadArtifactsBlock.getDefaultArtifactTags();
    }
    return artifactTags;
  }

  public boolean isOverwriteExistingFiles()
  {
    return overwriteExistingFiles;
  }

  public boolean isLinkToLocalFiles()
  {
    return linkToLocalFiles;
  }

  public Descriptor<UploadArtifactsBlock> getDescriptor()
  {
    return Jenkins.getInstance().getDescriptor(UploadArtifactsBlock.class);
  }

  @Extension
  public static class DescriptorImpl extends Descriptor<UploadArtifactsBlock>
  {
    @Override
    public String getDisplayName()
    {
      // Unused. Provided for compatibility with f:repeatableProperty in the jelly config.
      return "";
    }

    public String getDefaultArtifactFolder()
    {
      return UploadArtifactsBlock.getDefaultArtifactFolder();
    }
  }
}
