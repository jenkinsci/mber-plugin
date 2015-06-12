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
import com.mber.client.MberJSON;
import hudson.FilePath;
import hudson.model.BuildListener;
import hudson.remoting.VirtualChannel;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;

public class FileDownloadCallable implements FilePath.FileCallable<JSONObject>, LoggingOutputStream.Listener
{
  private final String url;
  private final BuildListener listener;
  private String fileName;

  public FileDownloadCallable(final String url)
  {
    this.url = url;
    this.listener = null;
    this.fileName = null;
  }

  public FileDownloadCallable(final String url, final BuildListener listener)
  {
    this.url = url;
    this.listener = listener;
    this.fileName = null;
  }

  @Override
  public JSONObject invoke(final File file, final VirtualChannel channel)
  {
    InputStream istream = null;
    OutputStream ostream = null;
    try {
      this.fileName = file.getName();
      // Avoid I/O errors by setting attributes on the connection before getting data from it.
      final String redirectedURL = followRedirects(this.url);
      final URLConnection connection = new URL(redirectedURL).openConnection();
      connection.setUseCaches(false);

      // Track expected bytes vs. downloaded bytes so we can retry corrupt downloads.
      final long expectedByteCount = connection.getContentLength();
      istream = connection.getInputStream();
      ostream = new LoggingOutputStream(new FilePath(file).write(), this, expectedByteCount);
      final long downloadedByteCount = IOUtils.copyLarge(istream, ostream);

      if (downloadedByteCount < expectedByteCount) {
        final long missingByteCount = expectedByteCount - downloadedByteCount;
        return MberJSON.failed(String.format("Missing %d bytes in %s", missingByteCount, this.fileName));
      }

      return MberJSON.success();
    }
    catch (final Exception e) {
      return MberJSON.failed(e);
    }
    finally {
      // Close the input and output streams so other build steps can access those files.
      IOUtils.closeQuietly(istream);
      IOUtils.closeQuietly(ostream);
    }
  }

  @Override
  public void logPercentComplete(final int percent)
  {
    log("Downloaded %d%% of %s", percent, this.fileName);
  }

  private void log(final String message, final Object... args)
  {
    if (this.listener != null && !message.isEmpty()) {
      this.listener.getLogger().println(String.format(message, args));
    }
  }

  public static String followRedirects(final String url) throws IOException {
    // Turn off auto redirect follow so we can read the final URL ourselves.
    // The internal parsing doesn't work with some of the headers used by Amazon.
    final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setUseCaches(false);
    connection.setInstanceFollowRedirects(false);
    connection.connect();

    // Pull the redirect URL out of the "Location" header. Follow it recursively since it might be chained.
    if (connection.getResponseCode() == HttpURLConnection.HTTP_MOVED_PERM || connection.getResponseCode() == HttpURLConnection.HTTP_MOVED_TEMP) {
      final String redirectURL = connection.getHeaderField("Location");
      return followRedirects(redirectURL);
    }

    return url;
  }
}
