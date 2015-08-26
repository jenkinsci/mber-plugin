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
import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotSame;
import org.junit.Test;
import org.jvnet.hudson.test.HudsonTestCase;

public class MberUploaderTest extends HudsonTestCase {
  @Test
  public void testConfigRoundtrip() throws Exception
  {
    // Set up a global access profile so its name can be resolved by the build step's config.
    final MberAccessProfile accessProfile = new MberAccessProfile("name", "application", "username", "password", "url");
    MberNotifier.setOrAddAccessProfile(accessProfile);

    // Add a new build step to the project and make sure its config saves and loads.
    final FreeStyleProject project = createFreeStyleProject();
    final MberUploader before = new MberUploader(accessProfile.getName(), "files", "folder", "tags", true, true, true, true, 0);
    project.getBuildersList().add(before);
    configRoundtrip(project);
    final MberUploader after = project.getBuildersList().get(MberUploader.class);
    assertNotSame(before, after);
    assertEqualDataBoundBeans(before, after);
  }

  @Test
  public void testConfigDefaults() throws Exception
  {
    // Make sure the build step's config has sensible defaults for null values.
    MberUploader uploader = new MberUploader(null, null, null, null, false, false, false, false, 0);
    assertEquals("Provide a default upload folder for null values", MberUploader.getDefaultArtifactFolder(), uploader.getArtifactFolder());
    assertEquals("Provide default upload tags for null values", MberUploader.getDefaultArtifactTags(), uploader.getArtifactTags());

    // Make sure the build step's config has sensible defaults for null values.
    uploader = new MberUploader(null, null, "", "", false, false, false, false, 0);
    assertEquals("Provide a default upload folder for empty values", MberUploader.getDefaultArtifactFolder(), uploader.getArtifactFolder());
    assertEquals("Provide default upload tags for empty values", MberUploader.getDefaultArtifactTags(), uploader.getArtifactTags());
  }
}