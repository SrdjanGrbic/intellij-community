// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.log.data.index;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Couple;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.indexing.DataIndexer;
import com.intellij.util.indexing.StorageException;
import com.intellij.util.io.*;
import com.intellij.util.io.storage.AbstractStorage;
import com.intellij.vcs.log.data.VcsLogStorage;
import com.intellij.vcs.log.history.EdgeData;
import com.intellij.vcs.log.impl.VcsLogErrorHandler;
import com.intellij.vcs.log.impl.VcsLogIndexer;
import com.intellij.vcs.log.util.StorageId;
import com.intellij.vcsUtil.VcsFileUtil;
import com.intellij.vcsUtil.VcsUtil;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.function.ObjIntConsumer;

public final class VcsLogPathsIndex extends VcsLogFullDetailsIndex<List<VcsLogPathsIndex.ChangeKind>, VcsLogIndexer.CompressedDetails> {
  private static final Logger LOG = Logger.getInstance(VcsLogPathsIndex.class);
  public static final String PATHS = "paths";
  public static final String INDEX_PATHS_IDS = "paths-ids";
  public static final String RENAMES_MAP = "renames-map";

  @NotNull private final PathsIndexer myPathsIndexer;

  public VcsLogPathsIndex(@NotNull StorageId storageId,
                          @NotNull VcsLogStorage storage,
                          @NotNull Set<VirtualFile> roots,
                          @Nullable StorageLockContext storageLockContext,
                          @NotNull VcsLogErrorHandler errorHandler,
                          @NotNull Disposable disposableParent) throws IOException {
    super(storageId, PATHS, new PathsIndexer(storage, createPathsEnumerator(roots, storageId, storageLockContext),
                                             createRenamesMap(storageId, storageLockContext)),
          new ChangeKindListKeyDescriptor(), storageLockContext, errorHandler, disposableParent);

    myPathsIndexer = (PathsIndexer)myIndexer;
    myPathsIndexer.setFatalErrorConsumer(e -> errorHandler.handleError(VcsLogErrorHandler.Source.Index, e));
  }

  @NotNull
  private static PersistentEnumerator<LightFilePath> createPathsEnumerator(@NotNull Collection<VirtualFile> roots,
                                                                           @NotNull StorageId storageId,
                                                                           @Nullable StorageLockContext storageLockContext) throws IOException {
    Path storageFile = storageId.getStorageFile(INDEX_PATHS_IDS);
    return new PersistentEnumerator<>(storageFile, new LightFilePathKeyDescriptor(roots),
                                      AbstractStorage.PAGE_SIZE, storageLockContext, storageId.getVersion());
  }

  @NotNull
  private static PersistentHashMap<Couple<Integer>, Collection<Couple<Integer>>> createRenamesMap(@NotNull StorageId storageId,
                                                                                                  @Nullable StorageLockContext storageLockContext)
    throws IOException {
    Path storageFile = storageId.getStorageFile(RENAMES_MAP);
    return new PersistentHashMap<>(storageFile, new CoupleKeyDescriptor(), new CollectionDataExternalizer(), AbstractStorage.PAGE_SIZE,
                                   storageId.getVersion(), storageLockContext);
  }

  @Nullable
  private FilePath getPath(int pathId, boolean isDirectory) {
    try {
      return toFilePath(myPathsIndexer.getPathsEnumerator().valueOf(pathId), isDirectory);
    }
    catch (IOException e) {
      myPathsIndexer.myFatalErrorConsumer.consume(e);
    }
    return null;
  }

  @Override
  public void flush() throws StorageException {
    super.flush();
    myPathsIndexer.myRenamesMap.force();
    myPathsIndexer.getPathsEnumerator().force();
  }

  @Nullable
  public EdgeData<FilePath> findRename(int parent, int child, @NotNull VirtualFile root, @NotNull FilePath path, boolean isChildPath)
    throws IOException {
    Collection<Couple<Integer>> renames = myPathsIndexer.myRenamesMap.get(Couple.of(parent, child));
    if (renames == null) return null;
    int pathId = myPathsIndexer.myPathsEnumerator.enumerate(new LightFilePath(root, path));
    for (Couple<Integer> rename : renames) {
      if ((isChildPath && rename.second == pathId) ||
          (!isChildPath && rename.first == pathId)) {
        FilePath path1 = getPath(rename.first, path.isDirectory());
        FilePath path2 = getPath(rename.second, path.isDirectory());
        return new EdgeData<>(path1, path2);
      }
    }
    return null;
  }

  public void iterateCommits(@NotNull VirtualFile root, @NotNull FilePath path,
                             @NotNull ObjIntConsumer<? super List<ChangeKind>> consumer)
    throws IOException, StorageException {
    int pathId = myPathsIndexer.myPathsEnumerator.enumerate(new LightFilePath(root, path));
    iterateCommitIdsAndValues(pathId, consumer);
  }

  @NotNull
  VcsLogIndexer.PathsEncoder getPathsEncoder() {
    return new VcsLogIndexer.PathsEncoder() {
      @Override
      public int encode(@NotNull VirtualFile root, @NotNull String relativePath, boolean isDirectory) {
        try {
          return myPathsIndexer.myPathsEnumerator.enumerate(new LightFilePath(root, relativePath));
        }
        catch (IOException e) {
          myPathsIndexer.myFatalErrorConsumer.consume(e);
          return 0;
        }
      }
    };
  }

  @Override
  public void dispose() {
    super.dispose();
    try {
      myPathsIndexer.myRenamesMap.close();
      myPathsIndexer.getPathsEnumerator().close();
    }
    catch (IOException e) {
      LOG.warn(e);
    }
  }

  @Contract("null,_ -> null; !null,_ -> !null")
  @Nullable
  private static FilePath toFilePath(@Nullable LightFilePath lightFilePath, boolean isDirectory) {
    if (lightFilePath == null) return null;
    return VcsUtil.getFilePath(lightFilePath.getRoot().getPath() + "/" + lightFilePath.getRelativePath(), isDirectory);
  }

  private static final class PathsIndexer implements DataIndexer<Integer, List<ChangeKind>, VcsLogIndexer.CompressedDetails> {
    @NotNull private final VcsLogStorage myStorage;
    @NotNull private final PersistentEnumerator<LightFilePath> myPathsEnumerator;
    @NotNull private final PersistentHashMap<Couple<Integer>, Collection<Couple<Integer>>> myRenamesMap;
    @NotNull private Consumer<? super Exception> myFatalErrorConsumer = LOG::error;

    private PathsIndexer(@NotNull VcsLogStorage storage, @NotNull PersistentEnumerator<LightFilePath> enumerator,
                         @NotNull PersistentHashMap<Couple<Integer>, Collection<Couple<Integer>>> renamesMap) {
      myStorage = storage;
      myPathsEnumerator = enumerator;
      myRenamesMap = renamesMap;
    }

    public void setFatalErrorConsumer(@NotNull Consumer<? super Exception> fatalErrorConsumer) {
      myFatalErrorConsumer = fatalErrorConsumer;
    }

    @NotNull
    @Override
    public Map<Integer, List<ChangeKind>> map(@NotNull VcsLogIndexer.CompressedDetails inputData) {
      Int2ObjectMap<List<ChangeKind>> result = new Int2ObjectOpenHashMap<>();

      // its not exactly parents count since it is very convenient to assume that initial commit has one parent
      int parentsCount = inputData.getParents().isEmpty() ? 1 : inputData.getParents().size();
      for (int parentIndex = 0; parentIndex < parentsCount; parentIndex++) {
        try {
          Collection<Couple<Integer>> renames = new SmartList<>();
          for (Int2IntMap.Entry entry : inputData.getRenamedPaths(parentIndex).int2IntEntrySet()) {
            renames.add(Couple.of(entry.getIntKey(), entry.getIntValue()));
            getOrCreateChangeKindList(result, entry.getIntKey(), parentsCount).set(parentIndex, ChangeKind.REMOVED);
            getOrCreateChangeKindList(result, entry.getIntValue(), parentsCount).set(parentIndex, ChangeKind.ADDED);
          }

          if (renames.size() > 0) {
            int commit = myStorage.getCommitIndex(inputData.getId(), inputData.getRoot());
            int parent = myStorage.getCommitIndex(inputData.getParents().get(parentIndex), inputData.getRoot());
            myRenamesMap.put(Couple.of(parent, commit), renames);
          }

          for (Int2ObjectMap.Entry<Change.Type> entry : inputData.getModifiedPaths(parentIndex).int2ObjectEntrySet()) {
            getOrCreateChangeKindList(result, entry.getIntKey(), parentsCount).set(parentIndex, createChangeData(entry.getValue()));
          }
        }
        catch (IOException e) {
          myFatalErrorConsumer.consume(e);
        }
      }

      return result;
    }

    @NotNull
    private static List<ChangeKind> getOrCreateChangeKindList(@NotNull Int2ObjectMap<List<ChangeKind>> pathIdToChangeDataListsMap,
                                                              int pathId,
                                                              int parentsCount) {
      List<ChangeKind> changeDataList = pathIdToChangeDataListsMap.get(pathId);
      if (changeDataList == null) {
        if (parentsCount == 1) {
          changeDataList = new SmartList<>(ChangeKind.NOT_CHANGED);
        }
        else {
          changeDataList = new ArrayList<>(parentsCount);
          for (int i = 0; i < parentsCount; i++) {
            changeDataList.add(ChangeKind.NOT_CHANGED);
          }
        }
        pathIdToChangeDataListsMap.put(pathId, changeDataList);
      }
      return changeDataList;
    }

    @NotNull
    private static ChangeKind createChangeData(@NotNull Change.Type type) {
      switch (type) {
        case NEW:
          return ChangeKind.ADDED;
        case DELETED:
          return ChangeKind.REMOVED;
        default:
          return ChangeKind.MODIFIED;
      }
    }

    @NotNull
    public PersistentEnumerator<LightFilePath> getPathsEnumerator() {
      return myPathsEnumerator;
    }
  }

  private static class ChangeKindListKeyDescriptor implements DataExternalizer<List<ChangeKind>> {
    @Override
    public void save(@NotNull DataOutput out, List<ChangeKind> value) throws IOException {
      DataInputOutputUtil.writeINT(out, value.size());
      for (ChangeKind data : value) {
        out.writeByte(data.id);
      }
    }

    @Override
    public List<ChangeKind> read(@NotNull DataInput in) throws IOException {
      List<ChangeKind> value = new SmartList<>();

      int size = DataInputOutputUtil.readINT(in);
      for (int i = 0; i < size; i++) {
        value.add(ChangeKind.getChangeKindById(in.readByte()));
      }

      return value;
    }
  }

  public enum ChangeKind {
    MODIFIED((byte)0),
    NOT_CHANGED((byte)1), // we do not want to have nulls in lists
    ADDED((byte)2),
    REMOVED((byte)3);

    public final byte id;

    ChangeKind(byte id) {
      this.id = id;
    }

    private static final ChangeKind[] KINDS;

    static {
      KINDS = new ChangeKind[4];
      for (ChangeKind kind : values()) {
        KINDS[kind.id] = kind;
      }
    }

    @NotNull
    public static ChangeKind getChangeKindById(byte id) throws IOException {
      ChangeKind kind = id >= 0 && id < KINDS.length ? KINDS[id] : null;
      if (kind == null) throw new IOException("Change kind by id " + id + " not found.");
      return kind;
    }
  }

  private static final class LightFilePath {
    @NotNull private final VirtualFile myRoot;
    @NotNull private final String myRelativePath;

    private LightFilePath(@NotNull VirtualFile root, @NotNull String relativePath) {
      myRoot = root;
      myRelativePath = relativePath;
    }

    private LightFilePath(@NotNull VirtualFile root, @NotNull FilePath filePath) {
      this(root, VcsFileUtil.relativePath(root, filePath));
    }

    @NotNull
    public VirtualFile getRoot() {
      return myRoot;
    }

    @NotNull
    public String getRelativePath() {
      return myRelativePath;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      LightFilePath path = (LightFilePath)o;
      return myRoot.equals(path.myRoot) &&
             myRelativePath.equals(path.myRelativePath);
    }

    @Override
    public int hashCode() {
      return Objects.hash(myRoot, myRelativePath);
    }
  }

  private static final class LightFilePathKeyDescriptor implements KeyDescriptor<LightFilePath> {
    @NotNull private final List<VirtualFile> myRoots;
    @NotNull private final Object2IntMap<VirtualFile> myRootsReversed;

    private LightFilePathKeyDescriptor(@NotNull Collection<VirtualFile> roots) {
      myRoots = ContainerUtil.sorted(roots, Comparator.comparing(VirtualFile::getPath));

      myRootsReversed = new Object2IntOpenHashMap<>();
      for (int i = 0; i < myRoots.size(); i++) {
        myRootsReversed.put(myRoots.get(i), i);
      }
    }

    @Override
    public int getHashCode(LightFilePath path) {
      return path.hashCode();
    }

    @Override
    public boolean isEqual(@Nullable LightFilePath path1, @Nullable LightFilePath path2) {
      return Objects.equals(path1, path2);
    }

    @Override
    public void save(@NotNull DataOutput out, LightFilePath value) throws IOException {
      out.writeInt(myRootsReversed.getInt(value.getRoot()));
      IOUtil.writeUTF(out, value.getRelativePath());
    }

    @Override
    public LightFilePath read(@NotNull DataInput in) throws IOException {
      int rootIndex = in.readInt();
      VirtualFile root = myRoots.get(rootIndex);
      if (root == null) throw new IOException("Can not read root for index " + rootIndex + ". All roots " + myRoots);
      String path = IOUtil.readUTF(in);
      return new LightFilePath(root, path);
    }
  }

  private static class CoupleKeyDescriptor implements KeyDescriptor<Couple<Integer>> {
    @Override
    public int getHashCode(Couple<Integer> value) {
      return value.hashCode();
    }

    @Override
    public boolean isEqual(@Nullable Couple<Integer> val1, @Nullable Couple<Integer> val2) {
      return Objects.equals(val1, val2);
    }

    @Override
    public void save(@NotNull DataOutput out, Couple<Integer> value) throws IOException {
      out.writeInt(value.first);
      out.writeInt(value.second);
    }

    @Override
    public Couple<Integer> read(@NotNull DataInput in) throws IOException {
      return Couple.of(in.readInt(), in.readInt());
    }
  }

  private static class CollectionDataExternalizer implements DataExternalizer<Collection<Couple<Integer>>> {
    @Override
    public void save(@NotNull DataOutput out, Collection<Couple<Integer>> value) throws IOException {
      out.writeInt(value.size());
      for (Couple<Integer> v : value) {
        out.writeInt(v.first);
        out.writeInt(v.second);
      }
    }

    @Override
    public Collection<Couple<Integer>> read(@NotNull DataInput in) throws IOException {
      List<Couple<Integer>> result = new SmartList<>();
      int size = in.readInt();
      for (int i = 0; i < size; i++) {
        result.add(Couple.of(in.readInt(), in.readInt()));
      }
      return result;
    }
  }
}
