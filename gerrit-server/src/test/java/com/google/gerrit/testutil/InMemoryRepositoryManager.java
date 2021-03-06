// Copyright (C) 2013 The Android Open Source Project
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

package com.google.gerrit.testutil;

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.RepositoryCaseMismatchException;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Repository;

import java.util.Map;
import java.util.SortedSet;

/** Repository manager that uses in-memory repositories. */
public class InMemoryRepositoryManager implements GitRepositoryManager {
  private static class Description extends DfsRepositoryDescription {
    private String desc;

    private Description(Project.NameKey name) {
      super(name.get());
      desc = "In-memory repository " + name.get();
    }
  }

  private static class Repo extends InMemoryRepository {
    private Repo(Project.NameKey name) {
      super(new Description(name));
    }

    @Override
    public Description getDescription() {
      return (Description) super.getDescription();
    }
  }

  private Map<String, Repo> repos = Maps.newHashMap();

  @Override
  public Repository openRepository(Project.NameKey name)
      throws RepositoryNotFoundException {
    return get(name);
  }

  @Override
  public Repository createRepository(Project.NameKey name)
      throws RepositoryCaseMismatchException, RepositoryNotFoundException {
    Repo repo;
    try {
      repo = get(name);
      if (!repo.getDescription().getRepositoryName().equals(name.get())) {
        throw new RepositoryCaseMismatchException(name);
      }
    } catch (RepositoryNotFoundException e) {
      repo = new Repo(name);
      repos.put(name.get().toLowerCase(), repo);
    }
    return repo;
  }

  @Override
  public SortedSet<Project.NameKey> list() {
    SortedSet<Project.NameKey> names = Sets.newTreeSet();
    for (DfsRepository repo : repos.values()) {
      names.add(new Project.NameKey(repo.getDescription().getRepositoryName()));
    }
    return ImmutableSortedSet.copyOf(names);
  }

  @Override
  public String getProjectDescription(Project.NameKey name)
      throws RepositoryNotFoundException {
    return get(name).getDescription().desc;
  }

  @Override
  public void setProjectDescription(Project.NameKey name, String description) {
    try {
      get(name).getDescription().desc = description;
    } catch (RepositoryNotFoundException e) {
      // Ignore.
    }
  }

  private Repo get(Project.NameKey name) throws RepositoryNotFoundException {
    Repo repo = repos.get(name.get().toLowerCase());
    if (repo != null) {
      return repo;
    } else {
      throw new RepositoryNotFoundException(name.get());
    }
  }
}
