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
import org.junit.Assert;
import org.junit.Test;
import java.util.Random;

public class RetryableTest {
  @Test
  public void retriesOnNullAndExceptions() throws Exception
  {
    // Come up with some random number of times to retry.
    final Random prng = new Random();
    final int maxAttempts = prng.nextInt(9) + 1;

    // Make sure we retry if an exception is thrown.
    final CountingRetryable explodingRetryable = new CountingRetryable(maxAttempts) {
      @Override
      public Integer mockCall()
      {
        throw new RetryException(String.format("Exploding retry number %d", this.callCount));
      }
    };
    explodingRetryable.run();
    Assert.assertEquals("Retryables should retry on exceptions", maxAttempts, explodingRetryable.callCount);

    // Make sure we retry if a null value is returned.
    final CountingRetryable nullRetryable = new CountingRetryable(maxAttempts) {
      @Override
      public Integer mockCall()
      {
        return null;
      }
    };
    nullRetryable.run();
    Assert.assertEquals("Retryables should retry on null returns", maxAttempts, nullRetryable.callCount);
  }

  @Test
  public void retriesOnNonPositiveAttempts() throws Exception
  {
    // Make sure we retry at least once if no attempts are provided.
    final CountingRetryable zeroRetryable = new CountingRetryable(0) {
      @Override
      public Integer mockCall()
      {
        return null;
      }
    };
    zeroRetryable.run();
    Assert.assertEquals("Retryables should retry at least once", 1, zeroRetryable.callCount);

    // Make sure we retry a positive number of times.
    final CountingRetryable absRetryable = new CountingRetryable(-3) {
      @Override
      public Integer mockCall()
      {
        return null;
      }
    };
    absRetryable.run();
    Assert.assertEquals("Retryables should retry a positive number of times", 3, absRetryable.callCount);
  }

  private abstract class CountingRetryable extends Retryable<Integer>
  {
    public int callCount;

    public CountingRetryable(final int maxRetrys)
    {
      super(null, maxRetrys, 0);
      this.callCount = 0;
    }

    @Override
    public Integer call()
    {
      this.callCount += 1;
      return mockCall();
    }

    protected abstract Integer mockCall();
  }
}
