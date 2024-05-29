/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.MergePolicy.MergeContext;
import org.apache.lucene.index.MergePolicy.MergeSpecification;
import org.apache.lucene.index.MergePolicy.OneMerge;
import org.apache.lucene.store.Directory;
import org.apache.lucene.tests.analysis.MockAnalyzer;
import org.apache.lucene.tests.index.BaseMergePolicyTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;

public class TestTieredMergePolicy extends BaseMergePolicyTestCase {

  @Override
  public TieredMergePolicy mergePolicy() {
    return newTieredMergePolicy();
  }

  @Override
  protected void assertSegmentInfos(MergePolicy policy, SegmentInfos infos) throws IOException {
    TieredMergePolicy tmp = (TieredMergePolicy) policy;

    final long maxMergedSegmentBytes = (long) (tmp.getMaxMergedSegmentMB() * 1024 * 1024);

    long minSegmentBytes = Long.MAX_VALUE;
    int totalDelCount = 0;
    int totalMaxDoc = 0;
    long totalBytes = 0;
    List<Long> segmentSizes = new ArrayList<>();
    for (SegmentCommitInfo sci : infos) {
      totalDelCount += sci.getDelCount();
      totalMaxDoc += sci.info.maxDoc();
      long byteSize = sci.sizeInBytes();
      double liveRatio = 1 - (double) sci.getDelCount() / sci.info.maxDoc();
      long weightedByteSize = (long) (liveRatio * byteSize);
      totalBytes += weightedByteSize;
      segmentSizes.add(weightedByteSize);
      minSegmentBytes = Math.min(minSegmentBytes, weightedByteSize);
    }

    final double delPercentage = 100.0 * totalDelCount / totalMaxDoc;
    assertTrue(
        "Percentage of deleted docs "
            + delPercentage
            + " is larger than the target: "
            + tmp.getDeletesPctAllowed(),
        delPercentage <= tmp.getDeletesPctAllowed());

    assertTrue(
        "Number of segments "
            + infos.size()
            + " is lower than the minimum: "
            + tmp.getTargetSearchConcurrency(),
        infos.size() >= tmp.getTargetSearchConcurrency());

    long levelSizeBytes = Math.max(minSegmentBytes, (long) (tmp.getFloorSegmentMB() * 1024 * 1024));
    long bytesLeft = totalBytes;
    double allowedSegCount = 0;
    // below we make the assumption that segments that reached the max segment
    // size divided by 2 don't need merging anymore
    int mergeFactor = (int) Math.min(tmp.getSegmentsPerTier(), tmp.getMaxMergeAtOnce());
    while (true) {
      final double segCountLevel = bytesLeft / (double) levelSizeBytes;
      if (segCountLevel < tmp.getSegmentsPerTier() || levelSizeBytes >= maxMergedSegmentBytes / 2) {
        allowedSegCount += Math.ceil(segCountLevel);
        break;
      }
      allowedSegCount += tmp.getSegmentsPerTier();
      bytesLeft -= tmp.getSegmentsPerTier() * levelSizeBytes;
      levelSizeBytes = Math.min(levelSizeBytes * mergeFactor, maxMergedSegmentBytes / 2);
    }
    allowedSegCount = Math.max(allowedSegCount, tmp.getSegmentsPerTier());

    // It's ok to be over the allowed segment count if none of the most balanced merges are balanced
    // enough
    Collections.sort(segmentSizes);
    boolean hasBalancedMerges = false;
    for (int i = 0; i < segmentSizes.size() - mergeFactor; ++i) {
      long maxMergeSegmentSize = segmentSizes.get(i + mergeFactor - 1);
      if (maxMergeSegmentSize >= maxMergedSegmentBytes / 2) {
        break;
      }
      long totalMergeSize = 0;
      for (int j = 0; j < i + mergeFactor; ++j) {
        totalMergeSize += segmentSizes.get(j);
      }
      if (maxMergedSegmentBytes * 1.5 <= totalMergeSize) {
        hasBalancedMerges = true;
        break;
      }
    }

    int numSegments = infos.asList().size();
    assertTrue(
        String.format(
            Locale.ROOT,
            "mergeFactor=%d minSegmentBytes=%,d maxMergedSegmentBytes=%,d segmentsPerTier=%g maxMergeAtOnce=%d numSegments=%d allowed=%g totalBytes=%,d delPercentage=%g deletesPctAllowed=%g targetNumSegments=%d",
            mergeFactor,
            minSegmentBytes,
            maxMergedSegmentBytes,
            tmp.getSegmentsPerTier(),
            tmp.getMaxMergeAtOnce(),
            numSegments,
            allowedSegCount,
            totalBytes,
            delPercentage,
            tmp.getDeletesPctAllowed(),
            tmp.getTargetSearchConcurrency()),
        numSegments <= allowedSegCount || hasBalancedMerges == false);
  }

  @Override
  protected void assertMerge(MergePolicy policy, MergeSpecification merges) {
    TieredMergePolicy tmp = (TieredMergePolicy) policy;
    final int mergeFactor = (int) Math.min(tmp.getMaxMergeAtOnce(), tmp.getSegmentsPerTier());
    for (OneMerge merge : merges.merges) {
      assertTrue(merge.segments.size() <= mergeFactor);
    }
    assertTrue(merges.merges.size() >= tmp.getTargetSearchConcurrency());
  }

  public void testForceMergeDeletes() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig conf = newIndexWriterConfig(new MockAnalyzer(random()));
    TieredMergePolicy tmp = newTieredMergePolicy();
    conf.setMergePolicy(tmp);
    conf.setMaxBufferedDocs(4);
    tmp.setMaxMergeAtOnce(100);
    tmp.setSegmentsPerTier(100);
    tmp.setDeletesPctAllowed(50.0);
    tmp.setForceMergeDeletesPctAllowed(30.0);
    IndexWriter w = new IndexWriter(dir, conf);
    for (int i = 0; i < 80; i++) {
      Document doc = new Document();
      doc.add(newTextField("content", "aaa " + (i % 4), Field.Store.NO));
      w.addDocument(doc);
    }
    assertEquals(80, w.getDocStats().maxDoc);
    assertEquals(80, w.getDocStats().numDocs);

    if (VERBOSE) {
      System.out.println("\nTEST: delete docs");
    }
    w.deleteDocuments(new Term("content", "0"));
    w.forceMergeDeletes();

    assertEquals(80, w.getDocStats().maxDoc);
    assertEquals(60, w.getDocStats().numDocs);

    if (VERBOSE) {
      System.out.println("\nTEST: forceMergeDeletes2");
    }
    ((TieredMergePolicy) w.getConfig().getMergePolicy()).setForceMergeDeletesPctAllowed(10.0);
    w.forceMergeDeletes();
    assertEquals(60, w.getDocStats().maxDoc);
    assertEquals(60, w.getDocStats().numDocs);
    w.close();
    dir.close();
  }

  public void testPartialMerge() throws Exception {
    int num = atLeast(10);
    for (int iter = 0; iter < num; iter++) {
      if (VERBOSE) {
        System.out.println("TEST: iter=" + iter);
      }
      Directory dir = newDirectory();
      IndexWriterConfig conf = newIndexWriterConfig(new MockAnalyzer(random()));
      conf.setMergeScheduler(new SerialMergeScheduler());
      TieredMergePolicy tmp = newTieredMergePolicy();
      conf.setMergePolicy(tmp);
      conf.setMaxBufferedDocs(2);
      tmp.setMaxMergeAtOnce(3);
      tmp.setSegmentsPerTier(6);
      tmp.setTargetSearchConcurrency(TestUtil.nextInt(random(), 1, 5));

      IndexWriter w = new IndexWriter(dir, conf);
      int maxCount = 0;
      final int numDocs = TestUtil.nextInt(random(), 20, 100);
      for (int i = 0; i < numDocs; i++) {
        Document doc = new Document();
        doc.add(newTextField("content", "aaa " + (i % 4), Field.Store.NO));
        w.addDocument(doc);
        int count = w.getSegmentCount();
        maxCount = Math.max(count, maxCount);
        assertTrue("count=" + count + " maxCount=" + maxCount, count >= maxCount - 3);
      }
      assertTrue(
          "num segments=" + w.getSegmentCount() + " minNumSegments=" + tmp.getTargetSearchConcurrency(),
          tmp.getTargetSearchConcurrency() <= w.getSegmentCount());

      w.flush(true, true);

      int segmentCount = w.getSegmentCount();
      int targetCount = TestUtil.nextInt(random(), 1, segmentCount);

      if (VERBOSE) {
        System.out.println(
            "TEST: merge to " + targetCount + " segs (current count=" + segmentCount + ")");
      }
      w.forceMerge(targetCount);

      final double maxSegmentSize = Math.max(tmp.getMaxMergedSegmentMB(), tmp.getFloorSegmentMB());
      final long max125Pct = (long) ((maxSegmentSize * 1024.0 * 1024.0) * 1.25);
      // Other than in the case where the target count is 1 we can't say much except no segment
      // should be > 125% of max seg size.
      if (targetCount == 1) {
        assertEquals("Should have merged down to one segment", targetCount, w.getSegmentCount());
      } else {
        // why can't we say much? Well...
        // 1> the random numbers generated above mean we could have 10 segments and a target max
        // count of, say, 9. we
        //    could get there by combining only 2 segments. So tests like "no pair of segments
        // should total less than
        //    125% max segment size" aren't valid.
        //
        // 2> We could have 10 segments and a target count of 2. In that case there could be 5
        // segments resulting.
        //    as long as they're all < 125% max seg size, that's valid.
        for (SegmentCommitInfo info : w.cloneSegmentInfos()) {
          assertTrue(
              "No segment should be more than 125% of max segment size ",
              max125Pct >= info.sizeInBytes());
        }
      }

      w.close();
      dir.close();
    }
  }

  public void testForceMergeDeletesMaxSegSize() throws Exception {
    final Directory dir = newDirectory();
    final IndexWriterConfig conf = newIndexWriterConfig(new MockAnalyzer(random()));
    final TieredMergePolicy tmp = new TieredMergePolicy();
    tmp.setMaxMergedSegmentMB(0.01);
    tmp.setForceMergeDeletesPctAllowed(0.0);
    conf.setMergePolicy(tmp);

    final IndexWriter w = new IndexWriter(dir, conf);

    final int numDocs = atLeast(200);
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(newStringField("id", "" + i, Field.Store.NO));
      doc.add(newTextField("content", "aaa " + i, Field.Store.NO));
      w.addDocument(doc);
    }

    w.forceMerge(1);
    IndexReader r = DirectoryReader.open(w);
    assertEquals(numDocs, r.maxDoc());
    assertEquals(numDocs, r.numDocs());
    r.close();

    if (VERBOSE) {
      System.out.println("\nTEST: delete doc");
    }

    w.deleteDocuments(new Term("id", "" + (42 + 17)));

    r = DirectoryReader.open(w);
    assertEquals(numDocs, r.maxDoc());
    assertEquals(numDocs - 1, r.numDocs());
    r.close();

    w.forceMergeDeletes();

    r = DirectoryReader.open(w);
    assertEquals(numDocs - 1, r.maxDoc());
    assertEquals(numDocs - 1, r.numDocs());
    r.close();

    w.close();

    dir.close();
  }

  // LUCENE-7976 makes findForceMergeDeletes and findForcedDeletes respect max segment size by
  // default, so ensure that this works.
  public void testForcedMergesRespectSegSize() throws Exception {
    final Directory dir = newDirectory();
    final IndexWriterConfig conf = newIndexWriterConfig(new MockAnalyzer(random()));
    final TieredMergePolicy tmp = new TieredMergePolicy();

    // Empirically, 100 docs the size below give us segments of 3,330 bytes. It's not all that
    // reliable in terms
    // of how big a segment _can_ get, so set it to prevent merges on commit.
    double mbSize = 0.004;
    long maxSegBytes =
        (long)
            ((1024.0 * 1024.0)); // fudge it up, we're trying to catch egregious errors and segbytes
    // don't really reflect the number for original merges.
    tmp.setMaxMergedSegmentMB(mbSize);
    conf.setMaxBufferedDocs(100);
    conf.setMergePolicy(tmp);
    conf.setMergeScheduler(new SerialMergeScheduler());

    final IndexWriter w = new IndexWriter(dir, conf);

    final int numDocs = atLeast(2400);
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(newStringField("id", "" + i, Field.Store.NO));
      doc.add(newTextField("content", "aaa " + i, Field.Store.NO));
      w.addDocument(doc);
    }

    w.commit();

    // These should be no-ops on an index with no deletions and segments are pretty big.
    List<String> segNamesBefore = getSegmentNames(w);
    w.forceMergeDeletes();
    checkSegmentsInExpectations(w, segNamesBefore, false); // There should have been no merges.

    w.forceMerge(Integer.MAX_VALUE);
    checkSegmentsInExpectations(w, segNamesBefore, true);
    checkSegmentSizeNotExceeded(w.cloneSegmentInfos(), maxSegBytes);

    // Delete 12-17% of each segment and expungeDeletes. This should result in:
    // > the same number of segments as before.
    // > no segments larger than maxSegmentSize.
    // > no deleted docs left.
    int remainingDocs = numDocs - deletePctDocsFromEachSeg(w, random().nextInt(5) + 12, true);
    w.forceMergeDeletes();
    w.commit();
    checkSegmentSizeNotExceeded(w.cloneSegmentInfos(), maxSegBytes);
    assertFalse("There should be no deleted docs in the index.", w.hasDeletions());

    // Check that deleting _fewer_ than 10% doesn't merge inappropriately. Nothing should be merged
    // since no segment
    // has had more than 10% of its docs deleted.
    segNamesBefore = getSegmentNames(w);
    int deletedThisPass = deletePctDocsFromEachSeg(w, random().nextInt(4) + 3, false);
    w.forceMergeDeletes();
    remainingDocs -= deletedThisPass;
    checkSegmentsInExpectations(w, segNamesBefore, false); // There should have been no merges
    assertEquals(
        "NumDocs should reflect removed documents ", remainingDocs, w.getDocStats().numDocs);
    assertTrue(
        "Should still be deleted docs in the index",
        w.getDocStats().numDocs < w.getDocStats().maxDoc);

    // This time, forceMerge. By default, this should respect max segment size.
    // Will change for LUCENE-8236
    w.forceMerge(Integer.MAX_VALUE);
    checkSegmentSizeNotExceeded(w.cloneSegmentInfos(), maxSegBytes);

    // Now forceMerge down to one segment, there should be exactly remainingDocs in exactly one
    // segment.
    w.forceMerge(1);
    assertEquals("There should be exactly one segment now", 1, w.getSegmentCount());
    assertEquals(
        "maxDoc and numDocs should be identical", w.getDocStats().numDocs, w.getDocStats().maxDoc);
    assertEquals(
        "There should be an exact number of documents in that one segment",
        remainingDocs,
        w.getDocStats().numDocs);

    // Delete 5% and expunge, should be no change.
    segNamesBefore = getSegmentNames(w);
    remainingDocs -= deletePctDocsFromEachSeg(w, random().nextInt(5) + 1, false);
    w.forceMergeDeletes();
    checkSegmentsInExpectations(w, segNamesBefore, false);
    assertEquals("There should still be only one segment. ", 1, w.getSegmentCount());
    assertTrue(
        "The segment should have deleted documents",
        w.getDocStats().numDocs < w.getDocStats().maxDoc);

    w.forceMerge(1); // back to one segment so deletePctDocsFromEachSeg still works

    // Test singleton merge for expungeDeletes
    remainingDocs -= deletePctDocsFromEachSeg(w, random().nextInt(5) + 20, true);
    w.forceMergeDeletes();

    assertEquals("There should still be only one segment. ", 1, w.getSegmentCount());
    assertEquals(
        "The segment should have no deleted documents",
        w.getDocStats().numDocs,
        w.getDocStats().maxDoc);

    // sanity check, at this point we should have an over`-large segment, we know we have exactly
    // one.
    assertTrue("Our single segment should have quite a few docs", w.getDocStats().numDocs > 1_000);

    // Delete 60% of the documents and then add a few more docs and commit. This should "singleton
    // merge" the large segment
    // created above. 60% leaves some wriggle room, LUCENE-8263 will change this assumption and
    // should be tested
    // when we deal with that JIRA.

    deletedThisPass = deletePctDocsFromEachSeg(w, (w.getDocStats().numDocs * 60) / 100, true);
    remainingDocs -= deletedThisPass;

    for (int i = 0; i < 50; i++) {
      Document doc = new Document();
      doc.add(newStringField("id", "" + (i + numDocs), Field.Store.NO));
      doc.add(newTextField("content", "aaa " + i, Field.Store.NO));
      w.addDocument(doc);
    }

    w.commit(); // want to trigger merge no matter what.

    assertEquals(
        "There should be exactly one very large and one small segment",
        2,
        w.cloneSegmentInfos().size());
    SegmentCommitInfo info0 = w.cloneSegmentInfos().info(0);
    SegmentCommitInfo info1 = w.cloneSegmentInfos().info(1);
    int largeSegDocCount = Math.max(info0.info.maxDoc(), info1.info.maxDoc());
    int smallSegDocCount = Math.min(info0.info.maxDoc(), info1.info.maxDoc());
    assertEquals("The large segment should have a bunch of docs", largeSegDocCount, remainingDocs);
    assertEquals("Small segment should have fewer docs", smallSegDocCount, 50);

    w.close();

    dir.close();
  }

  public void testForcedMergesRespectMinSegmentCount() throws Exception {
    final Directory dir = newDirectory();
    final IndexWriterConfig conf = newIndexWriterConfig(new MockAnalyzer(random()));
    final TieredMergePolicy tmp = new TieredMergePolicy();

    tmp.setSegmentsPerTier(TestUtil.nextInt(random(), 2, 10));
    tmp.setTargetSearchConcurrency(TestUtil.nextInt(random(), 1, 10));
    int maxBufferedDocs = 100;
    conf.setMaxBufferedDocs(maxBufferedDocs);
    conf.setMergePolicy(tmp);
    conf.setMergeScheduler(new SerialMergeScheduler());

    final IndexWriter w = new IndexWriter(dir, conf);

    final int numDocs = atLeast(2400);
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(newStringField("id", "" + i, Field.Store.NO));
      doc.add(newTextField("content", "aaa " + i, Field.Store.NO));
      w.addDocument(doc);
    }

    w.commit();
    checkMinSegmentCountNotExceeded(w, tmp);

    // These should be no-ops on an index with no deletions and segments are pretty big.
    List<String> segNamesBefore = getSegmentNames(w);
    w.forceMergeDeletes();
    checkSegmentsInExpectations(w, segNamesBefore, false); // There should have been no merges.
    checkMinSegmentCountNotExceeded(w, tmp);

    w.forceMerge(Integer.MAX_VALUE);
    checkSegmentsInExpectations(w, segNamesBefore, true);
    checkMinSegmentCountNotExceeded(w, tmp);

    // Delete 12-17% of each segment and expungeDeletes. This should result in:
    // > no more segments than minSegmentCount.
    // > no deleted docs left.
    int remainingDocs = numDocs - deletePctDocsFromEachSeg(w, random().nextInt(5) + 12, true);
    w.forceMergeDeletes();
    w.commit();
    checkMinSegmentCountNotExceeded(w, tmp);
    assertFalse("There should be no deleted docs in the index.", w.hasDeletions());

    // Check that deleting _fewer_ than 10% doesn't merge inappropriately. Nothing should be merged
    // since no segment
    // has had more than 10% of its docs deleted.
    segNamesBefore = getSegmentNames(w);
    int deletedThisPass = deletePctDocsFromEachSeg(w, random().nextInt(4) + 3, false);
    w.forceMergeDeletes();
    remainingDocs -= deletedThisPass;
    checkSegmentsInExpectations(w, segNamesBefore, false); // There should have been no merges
    checkMinSegmentCountNotExceeded(w, tmp);
    assertEquals(
        "NumDocs should reflect removed documents ", remainingDocs, w.getDocStats().numDocs);
    assertTrue(
        "Should still be deleted docs in the index",
        w.getDocStats().numDocs < w.getDocStats().maxDoc);

    // This time, forceMerge. By default, this should respect min segment size.
    w.forceMerge(Integer.MAX_VALUE);
    checkMinSegmentCountNotExceeded(w, tmp);

    // forceMerge to minimum number of segments, should respect min segment size
    w.forceMerge(tmp.getTargetSearchConcurrency());
    checkMinSegmentCountNotExceeded(w, tmp);

    // Now forceMerge down to one segment, there should be exactly remainingDocs in exactly one
    // segment.
    w.forceMerge(1);
    assertEquals("There should be exactly one segment now", 1, w.getSegmentCount());
    assertEquals(
        "maxDoc and numDocs should be identical", w.getDocStats().numDocs, w.getDocStats().maxDoc);
    assertEquals(
        "There should be an exact number of documents in that one segment",
        remainingDocs,
        w.getDocStats().numDocs);

    w.close();

    dir.close();
  }

  /**
   * Checks that policy tries to retain the minimum number of segments
   */
  public void testTriesToMaintainMinSegmentCount() throws Exception {
    final Directory dir = newDirectory();
    final IndexWriterConfig conf = newIndexWriterConfig(new MockAnalyzer(random()));
    final TieredMergePolicy tmp = new TieredMergePolicy();

    tmp.setSegmentsPerTier(TestUtil.nextInt(random(), 2, 5));
    tmp.setTargetSearchConcurrency(TestUtil.nextInt(random(), 1, 10));
    conf.setMaxBufferedDocs(TestUtil.nextInt(random(), 10, 100));
    conf.setMergePolicy(tmp);
    conf.setMergeScheduler(new SerialMergeScheduler());

    final IndexWriter w = new IndexWriter(dir, conf);

    final int numDocs = atLeast(10_000);
    boolean checkMinSegments = false;
    for (int i = 0; i < numDocs; i++) {
      Document doc = new Document();
      doc.add(newStringField("id", "" + i, Field.Store.NO));
      doc.add(newTextField("content", "aaa " + i, Field.Store.NO));
      w.addDocument(doc);
      // Start checking when we have enough segments
      checkMinSegments = checkMinSegments | w.getSegmentCount() >= tmp.getTargetSearchConcurrency();
      if (checkMinSegments) {
        checkMinSegmentCountNotExceeded(w, tmp);
      }
    }

    // We should have enough segments as of now
    assertTrue(checkMinSegments);

    w.commit();
    checkMinSegmentCountNotExceeded(w, tmp);

    w.close();

    dir.close();
  }

  /**
   * Returns how many segments are in the index after applying all merges from the {@code spec} to
   * an index with {@code startingSegmentCount} segments
   */
  private int postMergesSegmentCount(int startingSegmentCount, MergeSpecification spec) {
    int count = startingSegmentCount;
    // remove the segments that are merged away
    for (var merge : spec.merges) {
      count -= merge.segments.size();
    }

    // add one for each newly merged segment
    count += spec.merges.size();

    return count;
  }

  // verify that all merges in the spec do not create a final merged segment size too much bigger
  // than the configured maxMergedSegmentSizeMb
  private static void assertMaxMergedSize(
      MergeSpecification specification,
      double maxMergedSegmentSizeMB,
      double indexTotalSizeInMB,
      int maxMergedSegmentCount)
      throws IOException {

    // When the two configs conflict, TMP favors the requested number of segments. I.e., it will
    // produce
    // too-large (> maxMergedSegmentMB) merged segments.
    double maxMBPerSegment = indexTotalSizeInMB / maxMergedSegmentCount;

    for (OneMerge merge : specification.merges) {
      // compute total size of all segments being merged
      long mergeTotalSizeInBytes = 0;
      for (var segment : merge.segments) {
        mergeTotalSizeInBytes += segment.sizeInBytes();
      }

      // we allow up to 50% "violation" of the configured maxMergedSegmentSizeMb (why? TMP itself is
      // on only adding 25% fudge factor):
      // TODO: drop this fudge factor back down to 25% -- TooMuchFudgeException!
      long limitBytes =
          (long) (1024 * 1024 * Math.max(maxMBPerSegment, maxMergedSegmentSizeMB) * 1.5);
      assertTrue(
          "mergeTotalSizeInBytes="
              + mergeTotalSizeInBytes
              + " limitBytes="
              + limitBytes
              + " maxMergedSegmentSizeMb="
              + maxMergedSegmentSizeMB,
          mergeTotalSizeInBytes < limitBytes);
    }
  }

  // LUCENE-8688 reports that force-merges merged more segments that necessary to respect
  // maxSegmentCount as a result
  // of LUCENE-7976, so we ensure that it only does the minimum number of merges here.
  public void testForcedMergesUseLeastNumberOfMerges() throws Exception {
    TieredMergePolicy tmp = new TieredMergePolicy();
    double oneSegmentSizeMB = 1.0D;
    double maxMergedSegmentSizeMB = 10 * oneSegmentSizeMB;
    tmp.setMaxMergedSegmentMB(maxMergedSegmentSizeMB);
    if (VERBOSE) {
      System.out.printf(Locale.ROOT, "TEST: maxMergedSegmentSizeMB=%.2f%n", maxMergedSegmentSizeMB);
    }

    // create simulated 30 segment index where each segment is 1 MB
    SegmentInfos infos = new SegmentInfos(Version.LATEST.major);
    int segmentCount = 30;
    for (int j = 0; j < segmentCount; j++) {
      infos.add(
          makeSegmentCommitInfo("_" + j, 1000, 0, oneSegmentSizeMB, IndexWriter.SOURCE_MERGE));
    }

    double indexTotalSizeMB = segmentCount * oneSegmentSizeMB;

    int maxSegmentCountAfterForceMerge = random().nextInt(10) + 3;
    if (VERBOSE) {
      System.out.println("TEST: maxSegmentCountAfterForceMerge=" + maxSegmentCountAfterForceMerge);
    }
    MergeSpecification specification =
        tmp.findForcedMerges(
            infos,
            maxSegmentCountAfterForceMerge,
            segmentsToMerge(infos),
            new MockMergeContext(SegmentCommitInfo::getDelCount));
    if (VERBOSE) {
      System.out.println("TEST: specification=" + specification);
    }
    assertMaxMergedSize(
        specification, maxMergedSegmentSizeMB, indexTotalSizeMB, maxSegmentCountAfterForceMerge);

    // verify we achieved exactly the max segment count post-force-merge (which is a bit odd -- the
    // API only ensures <= segments, not ==)
    // TODO: change this to a <= equality like the last assert below?
    assertEquals(
        maxSegmentCountAfterForceMerge, postMergesSegmentCount(infos.size(), specification));

    // now create many segments index, containing 0.1 MB sized segments
    infos = new SegmentInfos(Version.LATEST.major);
    final int manySegmentsCount = atLeast(100);
    if (VERBOSE) {
      System.out.println("TEST: manySegmentsCount=" + manySegmentsCount);
    }
    oneSegmentSizeMB = 0.1D;
    for (int j = 0; j < manySegmentsCount; j++) {
      infos.add(
          makeSegmentCommitInfo("_" + j, 1000, 0, oneSegmentSizeMB, IndexWriter.SOURCE_MERGE));
    }

    indexTotalSizeMB = manySegmentsCount * oneSegmentSizeMB;

    specification =
        tmp.findForcedMerges(
            infos,
            maxSegmentCountAfterForceMerge,
            segmentsToMerge(infos),
            new MockMergeContext(SegmentCommitInfo::getDelCount));
    if (VERBOSE) {
      System.out.println("TEST: specification=" + specification);
    }
    assertMaxMergedSize(
        specification, maxMergedSegmentSizeMB, indexTotalSizeMB, maxSegmentCountAfterForceMerge);
    assertTrue(
        postMergesSegmentCount(infos.size(), specification) >= maxSegmentCountAfterForceMerge);
  }

  // Make sure that TieredMergePolicy doesn't do the final merge while there are merges ongoing, but
  // does do non-final
  // merges while merges are ongoing.
  public void testForcedMergeWithPending() throws Exception {
    final TieredMergePolicy tmp = new TieredMergePolicy();
    final double maxSegmentSize = 10.0D;
    tmp.setMaxMergedSegmentMB(maxSegmentSize);

    SegmentInfos infos = new SegmentInfos(Version.LATEST.major);
    for (int j = 0; j < 30; ++j) {
      infos.add(makeSegmentCommitInfo("_" + j, 1000, 0, 1.0D, IndexWriter.SOURCE_MERGE));
    }
    final MockMergeContext mergeContext = new MockMergeContext(SegmentCommitInfo::getDelCount);
    mergeContext.setMergingSegments(Collections.singleton(infos.asList().get(0)));
    final int expectedCount = random().nextInt(10) + 3;
    final MergeSpecification specification =
        tmp.findForcedMerges(infos, expectedCount, segmentsToMerge(infos), mergeContext);
    // Since we have fewer than 30 (the max merge count) segments more than the final size this
    // would have been the final merge, so we check that it was prevented.
    assertNull(specification);
  }

  private static Map<SegmentCommitInfo, Boolean> segmentsToMerge(SegmentInfos infos) {
    final Map<SegmentCommitInfo, Boolean> segmentsToMerge = new HashMap<>();
    for (SegmentCommitInfo info : infos) {
      segmentsToMerge.put(info, Boolean.TRUE);
    }
    return segmentsToMerge;
  }

  // Having a segment with very few documents in it can happen because of the random nature of the
  // docs added to the index. For instance, let's say it just happens that the last segment has 3
  // docs in it.
  // It can easily be merged with a close-to-max sized segment during a forceMerge and still respect
  // the max segment
  // size.
  //
  // If the above is possible, the "twoMayHaveBeenMerged" will be true and we allow for a little
  // slop, checking that
  // exactly two segments are gone from the old list and exactly one is in the new list. Otherwise,
  // the lists must match
  // exactly.
  //
  // So forceMerge may not be a no-op, allow for that. There are two possibilities in forceMerge
  // only:
  // > there were no small segments, in which case the two lists will be identical
  // > two segments in the original list are replaced by one segment in the final list.
  //
  // finally, there are some cases of forceMerge where the expectation is that there be exactly no
  // differences.
  // this should be called after forceDeletesMerges with the boolean always false,
  // Depending on the state, forceMerge may call with the boolean true or false.

  void checkSegmentsInExpectations(
      IndexWriter w, List<String> segNamesBefore, boolean twoMayHaveBeenMerged) {

    List<String> segNamesAfter = getSegmentNames(w);

    if (twoMayHaveBeenMerged == false || segNamesAfter.size() == segNamesBefore.size()) {
      if (segNamesAfter.size() != segNamesBefore.size()) {
        fail("Segment lists different sizes!: " + segNamesBefore + " After list: " + segNamesAfter);
      }

      if (segNamesAfter.containsAll(segNamesBefore) == false) {
        fail(
            "Segment lists should be identical: "
                + segNamesBefore
                + " After list: "
                + segNamesAfter);
      }
      return;
    }

    // forceMerge merged a tiny segment into a not-quite-max-sized segment so check that:
    // Two segments in the before list have been merged into one segment in the after list.
    if (segNamesAfter.size() != segNamesBefore.size() - 1) {
      fail(
          "forceMerge didn't merge a small and large segment into one segment as expected: "
              + segNamesBefore
              + " After list: "
              + segNamesAfter);
    }

    // There should be exactly two segments in the before not in after and one in after not in
    // before.
    List<String> testBefore = new ArrayList<>(segNamesBefore);
    List<String> testAfter = new ArrayList<>(segNamesAfter);

    testBefore.removeAll(segNamesAfter);
    testAfter.removeAll(segNamesBefore);

    if (testBefore.size() != 2 || testAfter.size() != 1) {
      fail(
          "Expected two unique 'before' segments and one unique 'after' segment: "
              + segNamesBefore
              + " After list: "
              + segNamesAfter);
    }
  }

  List<String> getSegmentNames(IndexWriter w) {
    List<String> names = new ArrayList<>();
    for (SegmentCommitInfo info : w.cloneSegmentInfos()) {
      names.add(info.info.name);
    }
    return names;
  }

  // Deletes some docs from each segment
  int deletePctDocsFromEachSeg(IndexWriter w, int pct, boolean roundUp) throws IOException {
    IndexReader reader = DirectoryReader.open(w);
    List<Term> toDelete = new ArrayList<>();
    for (LeafReaderContext ctx : reader.leaves()) {
      toDelete.addAll(getRandTerms(ctx, pct, roundUp));
    }
    reader.close();

    Term[] termsToDel = new Term[toDelete.size()];
    toDelete.toArray(termsToDel);
    w.deleteDocuments(termsToDel);
    w.commit();
    return toDelete.size();
  }

  // Get me some Ids to delete.
  // So far this supposes that there are no deleted docs in the segment.
  // When the numbers of docs in segments is small, rounding matters. So tests that want over a
  // percentage
  // pass "true" for roundUp, tests that want to be sure they're under some limit pass false.
  private List<Term> getRandTerms(LeafReaderContext ctx, int pct, boolean roundUp)
      throws IOException {

    assertFalse("This method assumes no deleted documents", ctx.reader().hasDeletions());
    // The indeterminate last segment is a pain, if we're there the number of docs is much less than
    // we expect
    List<Term> ret = new ArrayList<>(100);

    double numDocs = ctx.reader().numDocs();
    double tmp = (numDocs * (double) pct) / 100.0;

    if (tmp <= 1.0) { // Calculations break down for segments with very few documents, the "tail end
      // Charlie"
      return ret;
    }
    int mod = (int) (numDocs / tmp);

    if (mod == 0) return ret;

    Terms terms = ctx.reader().terms("id");
    TermsEnum iter = terms.iterator();
    int counter = 0;

    // Small numbers are tricky, they're subject to off-by-one errors. bail if we're going to exceed
    // our target if we add another doc.
    int lim = (int) (numDocs * (double) pct / 100.0);
    if (roundUp) ++lim;

    for (BytesRef br = iter.next(); br != null && ret.size() < lim; br = iter.next()) {
      if ((counter % mod) == 0) {
        ret.add(new Term("id", br));
      }
      ++counter;
    }
    return ret;
  }

  private void checkSegmentSizeNotExceeded(SegmentInfos infos, long maxSegBytes)
      throws IOException {
    for (SegmentCommitInfo info : infos) {
      // assertTrue("Found an unexpectedly large segment: " + info.toString(), info.info.maxDoc() -
      // info.getDelCount() <= docLim);
      assertTrue(
          "Found an unexpectedly large segment: " + info.toString(),
          info.sizeInBytes() <= maxSegBytes);
    }
  }

  private static void checkMinSegmentCountNotExceeded(IndexWriter writer, TieredMergePolicy tmp) {
    assertTrue(
        "There should be at least "
            + tmp.getTargetSearchConcurrency()
            + " segments, there are "
            + writer.getSegmentCount(),
            writer.getSegmentCount() >= tmp.getTargetSearchConcurrency());
  }

  private static final double EPSILON = 1E-14;

  public void testSetters() {
    final TieredMergePolicy tmp = new TieredMergePolicy();

    tmp.setMaxMergedSegmentMB(0.5);
    assertEquals(0.5, tmp.getMaxMergedSegmentMB(), EPSILON);

    tmp.setMaxMergedSegmentMB(Double.POSITIVE_INFINITY);
    assertEquals(
        Long.MAX_VALUE / 1024. / 1024., tmp.getMaxMergedSegmentMB(), EPSILON * Long.MAX_VALUE);

    tmp.setMaxMergedSegmentMB(Long.MAX_VALUE / 1024. / 1024.);
    assertEquals(
        Long.MAX_VALUE / 1024. / 1024., tmp.getMaxMergedSegmentMB(), EPSILON * Long.MAX_VALUE);

    expectThrows(
        IllegalArgumentException.class,
        () -> {
          tmp.setMaxMergedSegmentMB(-2.0);
        });

    tmp.setFloorSegmentMB(2.0);
    assertEquals(2.0, tmp.getFloorSegmentMB(), EPSILON);

    tmp.setFloorSegmentMB(Double.POSITIVE_INFINITY);
    assertEquals(Long.MAX_VALUE / 1024. / 1024., tmp.getFloorSegmentMB(), EPSILON * Long.MAX_VALUE);

    tmp.setFloorSegmentMB(Long.MAX_VALUE / 1024. / 1024.);
    assertEquals(Long.MAX_VALUE / 1024. / 1024., tmp.getFloorSegmentMB(), EPSILON * Long.MAX_VALUE);

    expectThrows(
        IllegalArgumentException.class,
        () -> {
          tmp.setFloorSegmentMB(-2.0);
        });

    tmp.setMaxCFSSegmentSizeMB(2.0);
    assertEquals(2.0, tmp.getMaxCFSSegmentSizeMB(), EPSILON);

    tmp.setMaxCFSSegmentSizeMB(Double.POSITIVE_INFINITY);
    assertEquals(
        Long.MAX_VALUE / 1024. / 1024., tmp.getMaxCFSSegmentSizeMB(), EPSILON * Long.MAX_VALUE);

    tmp.setMaxCFSSegmentSizeMB(Long.MAX_VALUE / 1024. / 1024.);
    assertEquals(
        Long.MAX_VALUE / 1024. / 1024., tmp.getMaxCFSSegmentSizeMB(), EPSILON * Long.MAX_VALUE);

    expectThrows(
        IllegalArgumentException.class,
        () -> {
          tmp.setMaxCFSSegmentSizeMB(-2.0);
        });

    // TODO: Add more checks for other non-double setters!
  }

  // LUCENE-5668
  public void testUnbalancedMergeSelection() throws Exception {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = new IndexWriterConfig(new MockAnalyzer(random()));
    TieredMergePolicy tmp = (TieredMergePolicy) iwc.getMergePolicy();
    tmp.setFloorSegmentMB(0.00001);
    // We need stable sizes for each segment:
    iwc.setCodec(TestUtil.getDefaultCodec());
    iwc.setMergeScheduler(new SerialMergeScheduler());
    iwc.setMaxBufferedDocs(100);
    iwc.setRAMBufferSizeMB(-1);
    IndexWriter w = new IndexWriter(dir, iwc);
    for (int i = 0; i < 15000 * RANDOM_MULTIPLIER; i++) {
      Document doc = new Document();
      // Incompressible content so that merging 10 segments of size x creates a segment whose size
      // is about 10x
      byte[] idBytes = new byte[128];
      random().nextBytes(idBytes);
      doc.add(new StoredField("id", idBytes));
      w.addDocument(doc);
    }
    IndexReader r = DirectoryReader.open(w);

    // Make sure TMP always merged equal-number-of-docs segments:
    for (LeafReaderContext ctx : r.leaves()) {
      int numDocs = ctx.reader().numDocs();
      assertTrue("got numDocs=" + numDocs, numDocs == 100 || numDocs == 1000 || numDocs == 10000);
    }
    r.close();
    w.close();
    dir.close();
  }

  public void testManyMaxSizeSegments() throws IOException {
    TieredMergePolicy policy = new TieredMergePolicy();
    policy.setMaxMergedSegmentMB(1024); // 1GB
    SegmentInfos infos = new SegmentInfos(Version.LATEST.major);
    int i = 0;
    for (int j = 0; j < 30; ++j) {
      infos.add(
          makeSegmentCommitInfo("_" + i, 1000, 0, 1024, IndexWriter.SOURCE_MERGE)); // max size
    }
    for (int j = 0; j < 8; ++j) {
      infos.add(
          makeSegmentCommitInfo("_" + i, 1000, 0, 102, IndexWriter.SOURCE_FLUSH)); // 102MB flushes
    }

    // Only 8 segments on 1 tier in addition to the max-size segments, nothing to do
    MergeSpecification mergeSpec =
        policy.findMerges(
            MergeTrigger.SEGMENT_FLUSH,
            infos,
            new MockMergeContext(SegmentCommitInfo::getDelCount));
    assertNull(mergeSpec);

    for (int j = 0; j < 5; ++j) {
      infos.add(
          makeSegmentCommitInfo("_" + i, 1000, 0, 102, IndexWriter.SOURCE_FLUSH)); // 102MB flushes
    }

    // Now 13 segments on 1 tier in addition to the max-size segments, 10 of them should get merged
    // in one merge
    mergeSpec =
        policy.findMerges(
            MergeTrigger.SEGMENT_FLUSH,
            infos,
            new MockMergeContext(SegmentCommitInfo::getDelCount));
    assertNotNull(mergeSpec);
    assertEquals(1, mergeSpec.merges.size());
    OneMerge merge = mergeSpec.merges.get(0);
    assertEquals(10, merge.segments.size());
  }

  /** Make sure that singleton merges are considered when the max number of deletes is crossed. */
  public void testMergePurelyToReclaimDeletes() throws IOException {
    TieredMergePolicy mergePolicy = mergePolicy();
    SegmentInfos infos = new SegmentInfos(Version.LATEST.major);
    // single 1GB segment with no deletes
    infos.add(makeSegmentCommitInfo("_0", 1_000_000, 0, 1024, IndexWriter.SOURCE_MERGE));

    // not eligible for merging
    assertNull(
        mergePolicy.findMerges(
            MergeTrigger.EXPLICIT, infos, new MockMergeContext(SegmentCommitInfo::getDelCount)));

    // introduce 15% deletes, still not eligible
    infos = applyDeletes(infos, (int) (0.15 * 1_000_000));
    assertNull(
        mergePolicy.findMerges(
            MergeTrigger.EXPLICIT, infos, new MockMergeContext(SegmentCommitInfo::getDelCount)));

    // now cross the delete threshold, becomes eligible
    infos =
        applyDeletes(
            infos, (int) ((mergePolicy.getDeletesPctAllowed() - 15 + 1) / 100 * 1_000_000));
    assertNotNull(
        mergePolicy.findMerges(
            MergeTrigger.EXPLICIT, infos, new MockMergeContext(SegmentCommitInfo::getDelCount)));
  }

  @Override
  public void testSimulateAppendOnly() throws IOException {
    TieredMergePolicy mergePolicy = mergePolicy();
    // Avoid low values of the max merged segment size which prevent this merge policy from scaling
    // well
    mergePolicy.setMaxMergedSegmentMB(TestUtil.nextInt(random(), 1024, 10 * 1024));
    doTestSimulateAppendOnly(mergePolicy, 100_000_000, 10_000);
  }

  @Override
  public void testSimulateUpdates() throws IOException {
    TieredMergePolicy mergePolicy = mergePolicy();
    // Avoid low values of the max merged segment size which prevent this merge policy from scaling
    // well
    mergePolicy.setMaxMergedSegmentMB(TestUtil.nextInt(random(), 1024, 10 * 1024));
    int numDocs = TEST_NIGHTLY ? atLeast(10_000_000) : atLeast(1_000_000);
    doTestSimulateUpdates(mergePolicy, numDocs, 2500);
  }

  public void testFullFlushMerges() throws IOException {
    AtomicLong segNameGenerator = new AtomicLong();
    IOStats stats = new IOStats();
    MergeContext mergeContext = new MockMergeContext(SegmentCommitInfo::getDelCount);
    SegmentInfos segmentInfos = new SegmentInfos(Version.LATEST.major);

    TieredMergePolicy mp = new TieredMergePolicy();

    for (int i = 0; i < 11; ++i) {
      segmentInfos.add(
          makeSegmentCommitInfo(
              "_" + segNameGenerator.getAndIncrement(),
              1,
              0,
              Double.MIN_VALUE,
              IndexWriter.SOURCE_FLUSH));
    }
    MergeSpecification spec =
        mp.findFullFlushMerges(MergeTrigger.FULL_FLUSH, segmentInfos, mergeContext);
    assertNotNull(spec);
    for (OneMerge merge : spec.merges) {
      segmentInfos =
          applyMerge(segmentInfos, merge, "_" + segNameGenerator.getAndIncrement(), stats);
    }
    assertEquals(2, segmentInfos.size());
  }
}
