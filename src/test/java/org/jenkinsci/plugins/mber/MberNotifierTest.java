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
import hudson.model.FreeStyleProject;
import java.util.ArrayList;
import java.util.List;
import org.jvnet.hudson.test.JenkinsRule;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MberNotifierTest
{
  @Rule
  public JenkinsRule jenkinsRule = new JenkinsRule();

  @Test
  public void testGlobalConfigDefaults() throws Exception
  {
    // Make sure the global config has valid default parameters after being saved.
    MberNotifier.DescriptorImpl before = getGlobalConfig();
    submitGlobalConfig();
    MberNotifier.DescriptorImpl after = getGlobalConfig();
    List<MberAccessProfile> expected = new ArrayList<MberAccessProfile>();
    expected.add(new MberAccessProfile("", "", "", "", MberAccessProfile.getDefaultUrl()));
    List<MberAccessProfile> actual = after.getAccessProfiles();
    assertSameAccessProfiles(expected, actual);
  }

  @Test
  public void testGlobalConfigRoundTrip() throws Exception
  {
    // Make sure the global config keeps explicit values after being saved.
    MberNotifier.DescriptorImpl before = getGlobalConfig();
    before.setOrAddAccessProfile(new MberAccessProfile("name", "application", "username", "password", "url"));
    List<MberAccessProfile> expected = before.getAccessProfiles();
    submitGlobalConfig();
    MberNotifier.DescriptorImpl after = getGlobalConfig();
    List<MberAccessProfile> actual = after.getAccessProfiles();
    assertSameAccessProfiles(expected, actual);
  }

  @Test
  public void testAccessProfileDefaults() throws Exception
  {
    // Make null URLs resovle to default values.
    MberAccessProfile profileWithNullURL = new MberAccessProfile("", "", "", "", null);
    assertEquals("Provide a default URL for null values", MberAccessProfile.getDefaultUrl(), profileWithNullURL.getUrl());

    // Make empty URLs resovle to default values.
    MberAccessProfile profileWithEmptyURL = new MberAccessProfile("", "", "", "", "");
    assertEquals("Provide a default URL for empty values", MberAccessProfile.getDefaultUrl(), profileWithEmptyURL.getUrl());
  }

  @Test
  public void testConfigDefaults() throws Exception
  {
    // Make sure a project's config has sensible defaults.
    MberNotifier notifier = new MberNotifier(null, null, null, false, false, null);
    assertEquals("Provide a default upload folder", UploadArtifactsBlock.getDefaultArtifactFolder(), notifier.getArtifactFolder(0));
    assertEquals("Provide default upload tags", UploadArtifactsBlock.getDefaultArtifactTags(), notifier.getArtifactTags(0));
    assertEquals("Don't upload artifacts if none are given", false, notifier.isUploadArtifacts());
    assertNull("Has upload artifacts when none where given", notifier.getBuildArtifacts(0));
  }

  @Test
  public void testConfigArtifactUploads() throws Exception
  {
    // Make sure the artifact uploads are set correctly.
    List<UploadArtifactsBlock> artifacts = new ArrayList<UploadArtifactsBlock>();
    artifacts.add(new UploadArtifactsBlock("files", "folder", "${BUILD_NUMBER}", false, false));
    MberNotifier notifier = new MberNotifier(null, null, null, false, false, new UploadArtifactsFlag(artifacts));
    assertEquals("Upload folder wasn't set", "folder", notifier.getArtifactFolder(0));
    assertEquals("Upload file list wasn't set", "files", notifier.getBuildArtifacts(0));
  }

  @Test
  public void testConfigRoundtrip() throws Exception
  {
    FreeStyleProject project = jenkinsRule.createFreeStyleProject();
    List<UploadArtifactsBlock> artifacts = new ArrayList<UploadArtifactsBlock>();
    artifacts.add(new UploadArtifactsBlock("files", "folder", "${BUILD_NUMBER}", true, true));
    MberNotifier before = new MberNotifier("access profile", "build name", "build description", true, true, new UploadArtifactsFlag(artifacts));
    project.getPublishersList().add(before);
    submitProjectConfig(project);
    MberNotifier after = project.getPublishersList().get(MberNotifier.class);
    assertSame(before, after);
  }

  private MberNotifier.DescriptorImpl getGlobalConfig()
  {
    return jenkinsRule.getInstance().getDescriptorByType(MberNotifier.DescriptorImpl.class);
  }

  private void submitGlobalConfig() throws Exception
  {
    jenkinsRule.submit(jenkinsRule.createWebClient().goTo("configure").getFormByName("config"));
  }

  private void submitProjectConfig(final FreeStyleProject p) throws Exception
  {
    jenkinsRule.submit(jenkinsRule.createWebClient().getPage(p, "configure").getFormByName("config"));
  }

  private void assertSame(final MberNotifier expected, final MberNotifier actual)
  {
    assertEquals("Application didn't match", expected.getApplication(), actual.getApplication());
    assertEquals("Username didn't match", expected.getUsername(), actual.getUsername());
    assertEquals("Password didn't match", expected.getPassword(), actual.getPassword());
    assertSameUploadArtifacts(expected.getUploadDestinations(), actual.getUploadDestinations());
    assertEquals("Will upload console log didn't match", expected.isUploadConsoleLog(), actual.isUploadConsoleLog());
    assertEquals("Will upload test results didn't match", expected.isUploadTestResults(), actual.isUploadTestResults());
    assertEquals("Will upload artifacts didn't match", expected.isUploadArtifacts(), actual.isUploadArtifacts());
  }

  private void assertSameUploadArtifacts(final List<UploadArtifactsBlock> expected, final List<UploadArtifactsBlock> actual)
  {
    assertEquals("Upload destination lists aren't the same size", expected.size(), actual.size());
    for (int index = 0; index < expected.size(); ++index) {
      assertEquals(String.format("Upload artifacts for item number %d didn't match", index), expected.get(index).getBuildArtifacts(), actual.get(index).getBuildArtifacts());
      assertEquals(String.format("Upload artifact folder for item number %d didn't match", index), expected.get(index).getArtifactFolder(), actual.get(index).getArtifactFolder());
      assertEquals(String.format("Upload artifact tags for item number %d didn't match", index), expected.get(index).getArtifactTags(), actual.get(index).getArtifactTags());
      assertEquals(String.format("Upload artifact overwrite flag for item number %d didn't match", index), expected.get(index).isOverwriteExistingFiles(), actual.get(index).isOverwriteExistingFiles());
      assertEquals(String.format("Upload artifact link to local files flag for item number $d didn't match", index), expected.get(index).isLinkToLocalFiles(), actual.get(index).isLinkToLocalFiles());
    }
  }

  private void assertSameAccessProfiles(final List<MberAccessProfile> expected, final List<MberAccessProfile> actual)
  {
    assertEquals("Access profile lists aren't the same size", expected.size(), actual.size());
    for (int index = 0; index < expected.size(); ++index) {
      assertEquals(String.format("Access profile name for item number %d didn't match", index), expected.get(index).getName(), actual.get(index).getName());
      assertEquals(String.format("Access profile application for item number %d didn't match", index), expected.get(index).getApplication(), actual.get(index).getApplication());
      assertEquals(String.format("Access profile username for item number %d didn't match", index), expected.get(index).getUsername(), actual.get(index).getUsername());
      assertEquals(String.format("Access profile password for item number %d didn't match", index), expected.get(index).getPassword(), actual.get(index).getPassword());
      assertEquals(String.format("Access profile URL for item number %d didn't match", index), expected.get(index).getUrl(), actual.get(index).getUrl());
    }
  }
}
