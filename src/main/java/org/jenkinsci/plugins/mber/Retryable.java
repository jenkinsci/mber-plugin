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

import java.io.PrintStream;

// This implements an exponential backoff and retry algorithm with a signature
// similar to Callables. Retry's are capped at a two minute maximum wait time.
public abstract class Retryable<T> {
  private final PrintStream logger;
  private final int maxAttempts;
  private final int waitTime;

  public Retryable(final PrintStream logger, final int maxAttempts)
  {
    this.logger = logger;
    this.maxAttempts = maxAttempts;
    this.waitTime = 10;
  }

  public Retryable(final PrintStream logger, final int maxAttempts, final int waitTime)
  {
    this.logger = logger;
    this.maxAttempts = maxAttempts;
    this.waitTime = waitTime;
  }

  protected abstract T call();

  public T run()
  {
    return retry(1);
  }

  private T retry(final int attempt)
  {
    if (attempt > this.maxAttempts) {
      return null;
    }
    try {
      final T result = call();
      if (result != null) {
        return result;
      }
    }
    catch (final Exception e) {
      log(e.getLocalizedMessage());
    }
    if (this.waitTime > 0) {
      // Don't wait longer than two minutes between any retry attempt.
      final long waitTimeInSeconds = Math.min(attempt * this.waitTime, 120);
      log("Retrying in %d seconds... %d/%d", waitTimeInSeconds, attempt, this.maxAttempts);
      try {
        Thread.sleep(waitTimeInSeconds * 1000);
      }
      catch (final InterruptedException e) {
        // Users may cancel the job instead of waiting for it to retry.
        return null;
      }
    }
    return retry(attempt + 1);
  }

  private void log(final String message, final Object... args)
  {
    if (this.logger != null && !message.isEmpty()) {
      this.logger.println(String.format(message, args));
    }
  }
}

// Thrown when a error occurs and the operation should be retried.
class RetryException extends RuntimeException
{
  public RetryException(final String message)
  {
    super(message);
  }
}
