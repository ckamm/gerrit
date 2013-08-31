// Copyright (C) 2009 The Android Open Source Project
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
//

package com.google.gerrit.server.patch;

import com.google.common.cache.CacheLoader;
import com.google.gerrit.reviewdb.client.AccountDiffPreference.Whitespace;
import com.google.gerrit.reviewdb.client.Patch;
import com.google.gerrit.reviewdb.client.Patch.ChangeType;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.inject.Inject;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeFormatter;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ResolveMerger;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.FileHeader.PatchType;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.TemporaryBuffer;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class PatchListLoader extends CacheLoader<PatchListKey, PatchList> {
  static final Logger log = LoggerFactory.getLogger(PatchListLoader.class);

  private final GitRepositoryManager repoManager;

  @Inject
  PatchListLoader(GitRepositoryManager mgr) {
    repoManager = mgr;
  }

  @Override
  public PatchList load(final PatchListKey key) throws Exception {
    final Repository repo = repoManager.openRepository(key.projectKey);
    final ObjectId aId = key.getOldId();
    final ObjectId bId = key.getNewId();
    final Whitespace whitespace = key.getWhitespace();

    try {
      if (aId == null) {
        // Patches against base just use the plain diff
        return readPatchList(key, repo);
      } else {
        // When diffing two patch sets their base commit can differ. Then
        // the simple diff includes the - usually unrelated - changes between the
        // bases.
        // To remove these changes, we generate diffs between the patch sets (a2b),
        // the bases (aBase2bBase) as well as the patch sets and their bases
        // (aBase2a, bBase2b) and compute the interesting part of the a2b diff.
        final PatchList a2b = readPatchList(
            new PatchListKey(key.projectKey, aId, bId, whitespace), repo);
        final PatchList aBase2a = readPatchList(
            new PatchListKey(key.projectKey, null, aId, whitespace), repo);
        final PatchList bBase2b = readPatchList(
            new PatchListKey(key.projectKey, null, bId, whitespace), repo);
        final PatchList aBase2bBase = readPatchList(
            new PatchListKey(key.projectKey, aBase2a.getOldId(), bBase2b.getOldId(), whitespace), repo);
        
        return interdiff(a2b, aBase2bBase, aBase2a, bBase2b);
      }
    } finally {
      repo.close();
    }
  }

  // Takes the a2b patch list and removes the changes in aBase2bBase from it.
  // To do that, it needs the aBase2a and bBase2b patch lists.
  private PatchList interdiff(
      final PatchList a2b,
      final PatchList aBase2bBase,
      final PatchList aBase2a,
      final PatchList bBase2b) {

    ArrayList<PatchListEntry> newPatches = new ArrayList<PatchListEntry>();

    // TODO: maps for fast access?

    for (PatchListEntry a2bPatch : a2b.getPatches()) {
      // the expected file name in aBase2a
      String expectedName = a2bPatch.getOldName() != null ? a2bPatch.getOldName()
                                                          : a2bPatch.getNewName();
    
      // find the cross patches, if any
      List<Edit> aEdits = new EditList();
      for (PatchListEntry p : aBase2a.getPatches()) {
        if (p.getNewName().equals(expectedName)) {
          aEdits = p.getEdits();
          break;
        }
      }

      // the expected file name in aBase2bBase (in bBase2b it's always a2b.newName)
      expectedName = a2bPatch.getNewName();
      List<Edit> bEdits = new EditList();
      for (PatchListEntry p : bBase2b.getPatches()) {
        if (p.getNewName().equals(a2bPatch.getNewName())) {
          bEdits = p.getEdits();
          expectedName = p.getOldName() != null ? p.getOldName() : p.getNewName();
          break;
        }
      }

      // Find the coresponding old base patch
      PatchListEntry basePatch = null;
      for (PatchListEntry p : aBase2bBase.getPatches()) {
        if (p.getNewName().equals(expectedName)) {
          basePatch = p;
          break;
        }
      }

      // Optimization for a very common case
      if (basePatch == null) {
        newPatches.add(a2bPatch);
        continue;
      }
      
      // Walk the newEdits and decide whether to keep them or not
      final List<Edit> baseEdits = basePatch.getEdits();
      final List<Edit> newEdits = a2bPatch.getEdits();
      EditList keptNewEdits = new EditList();

      // Indexes into aEdits, bEdits, baseEdits
      int aIndex = 0;
      int bIndex = 0;
      int baseIndex = 0;

      // Offsets between newEdits and baseEdits introduced by aEdits, bEdits.
      int aOffset = 0;
      int bOffset = 0;

      // Insert/delete counters.
      int inserts = 0;
      int deletes = 0;
      
      for (Edit newEdit : newEdits) {
        // 1. update offsets for a, b for all edits before newEdit
        // 2. if any aEdit or bEdit conflicts newEdit, keep newEdit
        // 3. if there's no identical baseEdit, keep newEdit
        // 4. otherwise newEdit can be dropped

        boolean conflictingEdit = false;

        // Handle relevant edits on A
        while (aIndex < aEdits.size()) {
          final Edit aEdit = aEdits.get(aIndex);

          if (aEdit.getEndB() <= newEdit.getBeginA()) {
            // Eat earlier, non-conflicting edits
            aOffset += aEdit.getLengthB() - aEdit.getLengthA();
            aIndex++;
          } else if (aEdit.getBeginB() >= newEdit.getEndA()) {
            // No relevant edits left
            break;
          } else {
            conflictingEdit = true;
            break;
          }
        }

        // Handle relevant edits on B
        while (!conflictingEdit && bIndex < bEdits.size()) {
          final Edit bEdit = bEdits.get(bIndex);

          if (bEdit.getEndB() <= newEdit.getBeginB()) {
            // Eat earlier, non-conflicting edits
            bOffset += bEdit.getLengthB() - bEdit.getLengthA();
            bIndex++;
          } else if (bEdit.getBeginB() >= newEdit.getEndB()) {
            // No relevant edits left
            break;
          } else {
            conflictingEdit = true;
            break;
          }
        }

        // Check for identical edit in baseEdits
        boolean identicalBaseEdit = false;
        while (!conflictingEdit && baseIndex < baseEdits.size()) {
          final Edit baseEdit = baseEdits.get(baseIndex);
          if (baseEdit.getBeginA() + aOffset > newEdit.getBeginA())
            break;

          if (baseEdit.getBeginA() + aOffset == newEdit.getBeginA() &&
              baseEdit.getBeginB() + bOffset == newEdit.getBeginB() &&
              baseEdit.getLengthA() == newEdit.getLengthA() &&
              baseEdit.getLengthB() == newEdit.getLengthB()) {
            identicalBaseEdit = true;
          }

          baseIndex++;
        }

        if (!identicalBaseEdit) {
            keptNewEdits.add(newEdit);
            inserts += newEdit.getLengthB();
            deletes += newEdit.getLengthA();
        }
      }

      if (!keptNewEdits.isEmpty()) {
        newPatches.add(new PatchListEntry(a2bPatch.getChangeType(),
            a2bPatch.getPatchType(), a2bPatch.getOldName(), a2bPatch.getNewName(),
            a2bPatch.getHeader(), keptNewEdits, inserts, deletes));
      }
    }

    return new PatchList(aBase2a.getNewId(), a2b.getNewId(), false,
                         newPatches.toArray(new PatchListEntry[newPatches.size()]));
  }

  private static RawTextComparator comparatorFor(Whitespace ws) {
    switch (ws) {
      case IGNORE_ALL_SPACE:
        return RawTextComparator.WS_IGNORE_ALL;

      case IGNORE_SPACE_AT_EOL:
        return RawTextComparator.WS_IGNORE_TRAILING;

      case IGNORE_SPACE_CHANGE:
        return RawTextComparator.WS_IGNORE_CHANGE;

      case IGNORE_NONE:
      default:
        return RawTextComparator.DEFAULT;
    }
  }

  private PatchList readPatchList(final PatchListKey key,
      final Repository repo) throws IOException {
    final RawTextComparator cmp = comparatorFor(key.getWhitespace());
    final ObjectReader reader = repo.newObjectReader();
    try {
      final RevWalk rw = new RevWalk(reader);
      final RevCommit b = rw.parseCommit(key.getNewId());
      final RevObject a = aFor(key, repo, rw, b);

      if (a == null) {
        // TODO(sop) Remove this case.
        // This is a merge commit, compared to its ancestor.
        //
        final PatchListEntry[] entries = new PatchListEntry[1];
        entries[0] = newCommitMessage(cmp, repo, reader, null, b);
        return new PatchList(a, b, true, entries);
      }

      final boolean againstParent =
          b.getParentCount() > 0 && b.getParent(0) == a;

      RevCommit aCommit;
      RevTree aTree;
      if (a instanceof RevCommit) {
        aCommit = (RevCommit) a;
        aTree = aCommit.getTree();
      } else if (a instanceof RevTree) {
        aCommit = null;
        aTree = (RevTree) a;
      } else {
        throw new IOException("Unexpected type: " + a.getClass());
      }

      RevTree bTree = b.getTree();

      final TreeWalk walk = new TreeWalk(reader);
      walk.reset();
      walk.setRecursive(true);
      walk.addTree(aTree);
      walk.addTree(bTree);
      walk.setFilter(TreeFilter.ANY_DIFF);

      DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
      df.setRepository(repo);
      df.setDiffComparator(cmp);
      df.setDetectRenames(true);
      List<DiffEntry> diffEntries = df.scan(aTree, bTree);

      final int cnt = diffEntries.size();
      final PatchListEntry[] entries = new PatchListEntry[1 + cnt];
      entries[0] = newCommitMessage(cmp, repo, reader, //
          againstParent ? null : aCommit, b);
      for (int i = 0; i < cnt; i++) {
        FileHeader fh = df.toFileHeader(diffEntries.get(i));
        entries[1 + i] = newEntry(aTree, fh);
      }
      return new PatchList(a, b, againstParent, entries);
    } finally {
      reader.release();
    }
  }

  private PatchListEntry newCommitMessage(final RawTextComparator cmp,
      final Repository db, final ObjectReader reader,
      final RevCommit aCommit, final RevCommit bCommit) throws IOException {
    StringBuilder hdr = new StringBuilder();

    hdr.append("diff --git");
    if (aCommit != null) {
      hdr.append(" a/" + Patch.COMMIT_MSG);
    } else {
      hdr.append(" " + FileHeader.DEV_NULL);
    }
    hdr.append(" b/" + Patch.COMMIT_MSG);
    hdr.append("\n");

    if (aCommit != null) {
      hdr.append("--- a/" + Patch.COMMIT_MSG + "\n");
    } else {
      hdr.append("--- " + FileHeader.DEV_NULL + "\n");
    }
    hdr.append("+++ b/" + Patch.COMMIT_MSG + "\n");

    Text aText =
        aCommit != null ? Text.forCommit(db, reader, aCommit) : Text.EMPTY;
    Text bText = Text.forCommit(db, reader, bCommit);

    byte[] rawHdr = hdr.toString().getBytes("UTF-8");
    RawText aRawText = new RawText(aText.getContent());
    RawText bRawText = new RawText(bText.getContent());
    EditList edits = new HistogramDiff().diff(cmp, aRawText, bRawText);
    FileHeader fh = new FileHeader(rawHdr, edits, PatchType.UNIFIED);
    return new PatchListEntry(fh, edits);
  }

  private PatchListEntry newEntry(RevTree aTree, FileHeader fileHeader) {
    final FileMode oldMode = fileHeader.getOldMode();
    final FileMode newMode = fileHeader.getNewMode();

    if (oldMode == FileMode.GITLINK || newMode == FileMode.GITLINK) {
      return new PatchListEntry(fileHeader, Collections.<Edit> emptyList());
    }

    if (aTree == null // want combined diff
        || fileHeader.getPatchType() != PatchType.UNIFIED
        || fileHeader.getHunks().isEmpty()) {
      return new PatchListEntry(fileHeader, Collections.<Edit> emptyList());
    }

    List<Edit> edits = fileHeader.toEditList();
    if (edits.isEmpty()) {
      return new PatchListEntry(fileHeader, Collections.<Edit> emptyList());
    } else {
      return new PatchListEntry(fileHeader, edits);
    }
  }

  private static RevObject aFor(final PatchListKey key,
      final Repository repo, final RevWalk rw, final RevCommit b)
      throws IOException {
    if (key.getOldId() != null) {
      return rw.parseAny(key.getOldId());
    }

    switch (b.getParentCount()) {
      case 0:
        return rw.parseAny(emptyTree(repo));
      case 1: {
        RevCommit r = b.getParent(0);
        rw.parseBody(r);
        return r;
      }
      case 2:
        return automerge(repo, rw, b);
      default:
        // TODO(sop) handle an octopus merge.
        return null;
    }
  }

  public static RevTree automerge(Repository repo, RevWalk rw, RevCommit b)
      throws IOException {
    return automerge(repo, rw, b, true);
  }

  public static RevTree automerge(Repository repo, RevWalk rw, RevCommit b,
      boolean save) throws IOException {
    String hash = b.name();
    String refName = GitRepositoryManager.REFS_CACHE_AUTOMERGE
        + hash.substring(0, 2)
        + "/"
        + hash.substring(2);
    Ref ref = repo.getRef(refName);
    if (ref != null && ref.getObjectId() != null) {
      return rw.parseTree(ref.getObjectId());
    }

    ObjectId treeId;
    ResolveMerger m = (ResolveMerger) MergeStrategy.RESOLVE.newMerger(repo, true);
    final ObjectInserter ins = repo.newObjectInserter();
    try {
      DirCache dc = DirCache.newInCore();
      m.setDirCache(dc);
      m.setObjectInserter(new ObjectInserter.Filter() {
        @Override
        protected ObjectInserter delegate() {
          return ins;
        }

        @Override
        public void flush() {
        }

        @Override
        public void release() {
        }
      });

      boolean couldMerge = false;
      try {
        couldMerge = m.merge(b.getParents());
      } catch (IOException e) {
        // It is not safe to continue further down in this method as throwing
        // an exception most likely means that the merge tree was not created
        // and m.getMergeResults() is empty. This would mean that all paths are
        // unmerged and Gerrit UI would show all paths in the patch list.
        return null;
      }

      if (couldMerge) {
        treeId = m.getResultTreeId();

      } else {
        RevCommit ours = b.getParent(0);
        RevCommit theirs = b.getParent(1);
        rw.parseBody(ours);
        rw.parseBody(theirs);
        String oursMsg = ours.getShortMessage();
        String theirsMsg = theirs.getShortMessage();

        String oursName = String.format("HEAD   (%s %s)",
            ours.abbreviate(6).name(),
            oursMsg.substring(0, Math.min(oursMsg.length(), 60)));
        String theirsName = String.format("BRANCH (%s %s)",
            theirs.abbreviate(6).name(),
            theirsMsg.substring(0, Math.min(theirsMsg.length(), 60)));

        MergeFormatter fmt = new MergeFormatter();
        Map<String, MergeResult<? extends Sequence>> r = m.getMergeResults();
        Map<String, ObjectId> resolved = new HashMap<String, ObjectId>();
        for (String path : r.keySet()) {
          MergeResult<? extends Sequence> p = r.get(path);
          TemporaryBuffer buf = new TemporaryBuffer.LocalFile(10 * 1024 * 1024);
          try {
            fmt.formatMerge(buf, p, "BASE", oursName, theirsName, "UTF-8");
            buf.close();

            InputStream in = buf.openInputStream();
            try {
              resolved.put(path, ins.insert(Constants.OBJ_BLOB, buf.length(), in));
            } finally {
              in.close();
            }
          } finally {
            buf.destroy();
          }
        }

        DirCacheBuilder builder = dc.builder();
        int cnt = dc.getEntryCount();
        for (int i = 0; i < cnt;) {
          DirCacheEntry entry = dc.getEntry(i);
          if (entry.getStage() == 0) {
            builder.add(entry);
            i++;
            continue;
          }

          int next = dc.nextEntry(i);
          String path = entry.getPathString();
          DirCacheEntry res = new DirCacheEntry(path);
          if (resolved.containsKey(path)) {
            // For a file with content merge conflict that we produced a result
            // above on, collapse the file down to a single stage 0 with just
            // the blob content, and a randomly selected mode (the lowest stage,
            // which should be the merge base, or ours).
            res.setFileMode(entry.getFileMode());
            res.setObjectId(resolved.get(path));

          } else if (next == i + 1) {
            // If there is exactly one stage present, shouldn't be a conflict...
            res.setFileMode(entry.getFileMode());
            res.setObjectId(entry.getObjectId());

          } else if (next == i + 2) {
            // Two stages suggests a delete/modify conflict. Pick the higher
            // stage as the automatic result.
            entry = dc.getEntry(i + 1);
            res.setFileMode(entry.getFileMode());
            res.setObjectId(entry.getObjectId());

          } else { // 3 stage conflict, no resolve above
            // Punt on the 3-stage conflict and show the base, for now.
            res.setFileMode(entry.getFileMode());
            res.setObjectId(entry.getObjectId());
          }
          builder.add(res);
          i = next;
        }
        builder.finish();
        treeId = dc.writeTree(ins);
      }
      ins.flush();
    } finally {
      ins.release();
    }

    if (save) {
      RefUpdate update = repo.updateRef(refName);
      update.setNewObjectId(treeId);
      update.disableRefLog();
      update.forceUpdate();
    }
    return rw.parseTree(treeId);
  }

  private static ObjectId emptyTree(final Repository repo) throws IOException {
    ObjectInserter oi = repo.newObjectInserter();
    try {
      ObjectId id = oi.insert(Constants.OBJ_TREE, new byte[] {});
      oi.flush();
      return id;
    } finally {
      oi.release();
    }
  }
}
