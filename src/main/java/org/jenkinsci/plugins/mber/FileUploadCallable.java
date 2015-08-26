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
import com.mber.client.HTTParty;
import com.mber.client.MberJSON;
import hudson.FilePath;
import hudson.model.BuildListener;
import hudson.remoting.VirtualChannel;
import java.io.File;
import net.sf.json.JSONObject;

public class FileUploadCallable implements FilePath.FileCallable<JSONObject>, LoggingOutputStream.Listener
{
  private final String url;
  private final BuildListener listener;
  private String fileName;

  public FileUploadCallable(String url)
  {
    this.url = url;
    this.listener = null;
    this.fileName = null;
  }

  public FileUploadCallable(String url, BuildListener listener)
  {
    this.url = url;
    this.listener = listener;
    this.fileName = null;
  }

  @Override
  public JSONObject invoke(File file, VirtualChannel channel)
  {
    try {
      this.fileName = file.getName();
      String response = HTTParty.put(this.url, file, this).body;
      if (response != null && !response.isEmpty()) {
        return MberJSON.failed(response);
      }
      JSONObject json = MberJSON.success();
      json.put("url", this.url);
      json.put("path", file.getAbsolutePath());
      return json;
    }
    catch (final LoggingInterruptedException e) {
      return MberJSON.aborted(e);
    }
    catch (final Exception e) {
      return MberJSON.failed(e);
    }
  }

  @Override
  public void logPercentComplete(final int percent)
  {
    log("Uploaded %d%% of %s", percent, this.fileName);
  }

  private void log(final String message, final Object... args)
  {
    if (this.listener != null && !message.isEmpty()) {
      this.listener.getLogger().println(String.format(message, args));
    }
  }
}
