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
import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class MberAccessProfile implements Describable<MberAccessProfile>
{
  private final String name;
  private final String application;
  private final String username;
  private final Secret password;
  private final String url;

  @DataBoundConstructor
  public MberAccessProfile(String name, String application, String username, String password, String url)
  {
    this.name = name;
    this.application = application;
    this.username = username;
    this.password = Secret.fromString(password);
    this.url = url;
  }

  public String getName()
  {
    return this.name;
  }

  public String getApplication()
  {
    return this.application;
  }

  public String getUsername()
  {
    return this.username;
  }

  public Secret getPassword()
  {
    return this.password;
  }

  public String getUrl()
  {
    if (this.url == null || this.url.isEmpty()) {
      return getDefaultUrl();
    }
    return this.url;
  }

  public static String getDefaultUrl()
  {
    return "https://member.firepub.net/";
  }

  public Descriptor<MberAccessProfile> getDescriptor()
  {
    return Jenkins.getInstance().getDescriptor(MberAccessProfile.class);
  }

  @Extension
  public static class DescriptorImpl extends Descriptor<MberAccessProfile>
  {
    @Override
    public String getDisplayName()
    {
      // Unused. Provided for compatibility with f:repeatableProperty in the jelly config.
      return "";
    }

    public FormValidation doCheckUrl(@QueryParameter String value)
    {
      if (MberClient.isMberURL(value)) {
        return FormValidation.ok();
      }
      return FormValidation.error("Invalid Mber URL. Clear the field and save the form to use the default value.");
    }

    public FormValidation doValidateLogin(@QueryParameter String application, @QueryParameter String username, @QueryParameter String password, @QueryParameter String url)
    {
      MberClient mber = new MberClient(url, application);
      JSONObject response = mber.login(username, Secret.fromString(password).getPlainText());
      if (response.getString("status").equals("Success")) {
        return FormValidation.ok("Success!");
      }
      return FormValidation.error(response.getString("error"));
    }

    // This is necessary so the jelly config file can populate a default URL value.
    // Since these are in the global configuration, only the descriptor is available.
    public String getDefaultUrl()
    {
      return MberAccessProfile.getDefaultUrl();
    }
  }
}
