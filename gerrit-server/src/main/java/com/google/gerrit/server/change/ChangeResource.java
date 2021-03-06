// Copyright (C) 2012 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.change;

import com.google.gerrit.extensions.restapi.RestResource;
import com.google.gerrit.extensions.restapi.RestView;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.server.project.ChangeControl;
import com.google.inject.TypeLiteral;

public class ChangeResource implements RestResource {
  public static final TypeLiteral<RestView<ChangeResource>> CHANGE_KIND =
      new TypeLiteral<RestView<ChangeResource>>() {};

  private final ChangeControl control;

  public ChangeResource(ChangeControl control) {
    this.control = control;
  }

  protected ChangeResource(ChangeResource copy) {
    this.control = copy.control;
  }

  public ChangeControl getControl() {
    return control;
  }

  public Change getChange() {
    return getControl().getChange();
  }
}
