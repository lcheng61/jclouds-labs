/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.jclouds.vsphere.util;

import org.testng.annotations.Test;

/**
 * Date: 29/06/2014 10:53 AM
 * Package: org.jclouds.vsphere.util
 */
@Test(groups = "unit", testName = "VSpherePredicateTest")
public class ComputerNameValidatorTest {
    @Test(expectedExceptions = IllegalArgumentException.class)
    public void validateTooShortTest() {
        ComputerNameValidator.INSTANCE.validate("a");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void validateTooLongTest() {
        ComputerNameValidator.INSTANCE.validate("asdfghjklzxcvbnmasdfghjkqwertyh");
    }

    public void validateOKTest() {
        ComputerNameValidator.INSTANCE.validate("good-name");
    }
}
