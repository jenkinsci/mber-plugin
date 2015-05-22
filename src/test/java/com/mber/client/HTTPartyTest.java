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

package com.mber.client;
import org.junit.Assert;
import org.junit.Test;

public class HTTPartyTest
{
  @Test
  public void encodesURIComponents() throws Exception
  {
    // Encoding for numbers shouldn't change them.
    String numbers = "0123456789";
    String encodedNumbers = HTTParty.encodeURIComponent(numbers);
    Assert.assertEquals("Encoding numbers as a URI component shouldn't change them", numbers, encodedNumbers);

    // Encoding for lowercase characters shouldn't change them.
    String lowerCaseLetters = "abcdefghijklmnopqrstuvwxyz";
    String encodedLowerCaseLetters = HTTParty.encodeURIComponent(lowerCaseLetters);
    Assert.assertEquals("Encoding lowercase letters as a URI component shouldn't change them", lowerCaseLetters, encodedLowerCaseLetters);

    // Encoding for uppercase characters shouldn't change them.
    String upperCaseLetters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    String encodedUpperCaseLetters = HTTParty.encodeURIComponent(upperCaseLetters);
    Assert.assertEquals("Encoding uppercase letters as a URI component shouldn't change them", upperCaseLetters, encodedUpperCaseLetters);

    // Encoding some symbols shouldn't change them.
    String unchangedSymbols = "~!*()_-'.";
    String encodedUnchangedSymbols = HTTParty.encodeURIComponent(unchangedSymbols);
    Assert.assertEquals("Encoding some symbols as a URI component shouldn't change them", unchangedSymbols, encodedUnchangedSymbols);

    // Encoding some symbols should change them.
    String changedSymbols = "@#$%^&+`={}|[]\\:\";<>?,/";
    String encodedChangedSymbols = HTTParty.encodeURIComponent(changedSymbols);
    String expectedEncodedChangedSymbols = "%40%23%24%25%5E%26%2B%60%3D%7B%7D%7C%5B%5D%5C%3A%22%3B%3C%3E%3F%2C%2F";
    Assert.assertEquals("Encoding some symbols as URI components should change them", expectedEncodedChangedSymbols, encodedChangedSymbols);
  }
}
