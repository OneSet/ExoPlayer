/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer.smoothstreaming;

import com.google.android.exoplayer.BehindLiveWindowException;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.TrackInfo;
import com.google.android.exoplayer.chunk.Chunk;
import com.google.android.exoplayer.chunk.ChunkExtractorWrapper;
import com.google.android.exoplayer.chunk.ChunkOperationHolder;
import com.google.android.exoplayer.chunk.ChunkSource;
import com.google.android.exoplayer.chunk.ContainerMediaChunk;
import com.google.android.exoplayer.chunk.Format;
import com.google.android.exoplayer.chunk.Format.DecreasingBandwidthComparator;
import com.google.android.exoplayer.chunk.FormatEvaluator;
import com.google.android.exoplayer.chunk.FormatEvaluator.Evaluation;
import com.google.android.exoplayer.chunk.MediaChunk;
import com.google.android.exoplayer.drm.DrmInitData;
import com.google.android.exoplayer.extractor.mp4.FragmentedMp4Extractor;
import com.google.android.exoplayer.extractor.mp4.Track;
import com.google.android.exoplayer.extractor.mp4.TrackEncryptionBox;
import com.google.android.exoplayer.smoothstreaming.SmoothStreamingManifest.ProtectionElement;
import com.google.android.exoplayer.smoothstreaming.SmoothStreamingManifest.StreamElement;
import com.google.android.exoplayer.smoothstreaming.SmoothStreamingManifest.TrackElement;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DataSpec;
import com.google.android.exoplayer.util.CodecSpecificDataUtil;
import com.google.android.exoplayer.util.ManifestFetcher;
import com.google.android.exoplayer.util.MimeTypes;

import android.net.Uri;
import android.os.SystemClock;
import android.util.Base64;
import android.util.SparseArray;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * An {@link ChunkSource} for SmoothStreaming.
 */
public class SmoothStreamingChunkSource implements ChunkSource {

  private static final int MINIMUM_MANIFEST_REFRESH_PERIOD_MS = 5000;
  private static final int INITIALIZATION_VECTOR_SIZE = 8;

  private final TrackInfo trackInfo;
  private final DataSource dataSource;
  private final FormatEvaluator formatEvaluator;
  private final Evaluation evaluation;
  private final long liveEdgeLatencyUs;
  private final int maxWidth;
  private final int maxHeight;

  private final SparseArray<ChunkExtractorWrapper> extractorWrappers;
  private final SparseArray<MediaFormat> mediaFormats;
  private final DrmInitData drmInitData;
  private final Format[] formats;

  private final ManifestFetcher<SmoothStreamingManifest> manifestFetcher;
  private final int streamElementIndex;

  private SmoothStreamingManifest currentManifest;
  private int currentManifestChunkOffset;
  private boolean finishedCurrentManifest;

  private IOException fatalError;

  /**
   * Constructor to use for live streaming.
   * <p>
   * May also be used for fixed duration content, in which case the call is equivalent to calling
   * the other constructor, passing {@code manifestFetcher.getManifest()} is the first argument.
   *
   * @param manifestFetcher A fetcher for the manifest, which must have already successfully
   *     completed an initial load.
   * @param streamElementIndex The index of the stream element in the manifest to be provided by
   *     the source.
   * @param trackIndices The indices of the tracks within the stream element to be considered by
   *     the source. May be null if all tracks within the element should be considered.
   * @param dataSource A {@link DataSource} suitable for loading the media data.
   * @param formatEvaluator Selects from the available formats.
   * @param liveEdgeLatencyMs For live streams, the number of milliseconds that the playback should
   *     lag behind the "live edge" (i.e. the end of the most recently defined media in the
   *     manifest). Choosing a small value will minimize latency introduced by the player, however
   *     note that the value sets an upper bound on the length of media that the player can buffer.
   *     Hence a small value may increase the probability of rebuffering and playback failures.
   */
  public SmoothStreamingChunkSource(ManifestFetcher<SmoothStreamingManifest> manifestFetcher,
      int streamElementIndex, int[] trackIndices, DataSource dataSource,
      FormatEvaluator formatEvaluator, long liveEdgeLatencyMs) {
    this(manifestFetcher, manifestFetcher.getManifest(), streamElementIndex, trackIndices,
        dataSource, formatEvaluator, liveEdgeLatencyMs);
  }

  /**
   * Constructor to use for fixed duration content.
   *
   * @param manifest The manifest parsed from {@code baseUrl + "/Manifest"}.
   * @param streamElementIndex The index of the stream element in the manifest to be provided by
   *     the source.
   * @param trackIndices The indices of the tracks within the stream element to be considered by
   *     the source. May be null if all tracks within the element should be considered.
   * @param dataSource A {@link DataSource} suitable for loading the media data.
   * @param formatEvaluator Selects from the available formats.
   */
  public SmoothStreamingChunkSource(SmoothStreamingManifest manifest, int streamElementIndex,
      int[] trackIndices, DataSource dataSource, FormatEvaluator formatEvaluator) {
    this(null, manifest, streamElementIndex, trackIndices, dataSource, formatEvaluator, 0);
  }

  private SmoothStreamingChunkSource(ManifestFetcher<SmoothStreamingManifest> manifestFetcher,
      SmoothStreamingManifest initialManifest, int streamElementIndex, int[] trackIndices,
      DataSource dataSource, FormatEvaluator formatEvaluator, long liveEdgeLatencyMs) {
    this.manifestFetcher = manifestFetcher;
    this.streamElementIndex = streamElementIndex;
    this.currentManifest = initialManifest;
    this.dataSource = dataSource;
    this.formatEvaluator = formatEvaluator;
    this.liveEdgeLatencyUs = liveEdgeLatencyMs * 1000;

    StreamElement streamElement = getElement(initialManifest);
    trackInfo = new TrackInfo(streamElement.tracks[0].format.mimeType, initialManifest.durationUs);
    evaluation = new Evaluation();

    TrackEncryptionBox[] trackEncryptionBoxes = null;
    ProtectionElement protectionElement = initialManifest.protectionElement;
    if (protectionElement != null) {
      byte[] keyId = getKeyId(protectionElement.data);
      trackEncryptionBoxes = new TrackEncryptionBox[1];
      trackEncryptionBoxes[0] = new TrackEncryptionBox(true, INITIALIZATION_VECTOR_SIZE, keyId);
      DrmInitData.Mapped drmInitData = new DrmInitData.Mapped(MimeTypes.VIDEO_MP4);
      drmInitData.put(protectionElement.uuid, protectionElement.data);
      this.drmInitData = drmInitData;
    } else {
      drmInitData = null;
    }

    int trackCount = trackIndices != null ? trackIndices.length : streamElement.tracks.length;
    formats = new Format[trackCount];
    extractorWrappers = new SparseArray<>();
    mediaFormats = new SparseArray<>();
    int maxWidth = 0;
    int maxHeight = 0;
    for (int i = 0; i < trackCount; i++) {
      int trackIndex = trackIndices != null ? trackIndices[i] : i;
      formats[i] = streamElement.tracks[trackIndex].format;
      maxWidth = Math.max(maxWidth, formats[i].width);
      maxHeight = Math.max(maxHeight, formats[i].height);

      MediaFormat mediaFormat = getMediaFormat(streamElement, trackIndex);
      int trackType = streamElement.type == StreamElement.TYPE_VIDEO ? Track.TYPE_VIDEO
          : Track.TYPE_AUDIO;
      FragmentedMp4Extractor extractor = new FragmentedMp4Extractor(
          FragmentedMp4Extractor.WORKAROUND_EVERY_VIDEO_FRAME_IS_SYNC_FRAME);
      extractor.setTrack(new Track(trackIndex, trackType, streamElement.timescale,
          initialManifest.durationUs, mediaFormat, trackEncryptionBoxes,
          trackType == Track.TYPE_VIDEO ? 4 : -1));
      extractorWrappers.put(trackIndex, new ChunkExtractorWrapper(extractor));
      mediaFormats.put(trackIndex, mediaFormat);
    }
    this.maxWidth = maxWidth;
    this.maxHeight = maxHeight;
    Arrays.sort(formats, new DecreasingBandwidthComparator());
  }

  @Override
  public final void getMaxVideoDimensions(MediaFormat out) {
    if (trackInfo.mimeType.startsWith("video")) {
      out.setMaxVideoDimensions(maxWidth, maxHeight);
    }
  }

  @Override
  public final TrackInfo getTrackInfo() {
    return trackInfo;
  }

  @Override
  public void enable() {
    fatalError = null;
    formatEvaluator.enable();
    if (manifestFetcher != null) {
      manifestFetcher.enable();
    }
  }

  @Override
  public void disable(List<? extends MediaChunk> queue) {
    formatEvaluator.disable();
    if (manifestFetcher != null) {
      manifestFetcher.disable();
    }
  }

  @Override
  public void continueBuffering(long playbackPositionUs) {
    if (manifestFetcher == null || !currentManifest.isLive || fatalError != null) {
      return;
    }

    SmoothStreamingManifest newManifest = manifestFetcher.getManifest();
    if (currentManifest != newManifest && newManifest != null) {
      StreamElement currentElement = getElement(currentManifest);
      int currentElementChunkCount = currentElement.chunkCount;
      StreamElement newElement = getElement(newManifest);
      if (currentElementChunkCount == 0 || newElement.chunkCount == 0) {
        // There's no overlap between the old and new elements because at least one is empty.
        currentManifestChunkOffset += currentElementChunkCount;
      } else {
        long currentElementEndTimeUs = currentElement.getStartTimeUs(currentElementChunkCount - 1)
            + currentElement.getChunkDurationUs(currentElementChunkCount - 1);
        long newElementStartTimeUs = newElement.getStartTimeUs(0);
        if (currentElementEndTimeUs <= newElementStartTimeUs) {
          // There's no overlap between the old and new elements.
          currentManifestChunkOffset += currentElementChunkCount;
        } else {
          // The new element overlaps with the old one.
          currentManifestChunkOffset += currentElement.getChunkIndex(newElementStartTimeUs);
        }
      }
      currentManifest = newManifest;
      finishedCurrentManifest = false;
    }

    if (finishedCurrentManifest && (SystemClock.elapsedRealtime()
        > manifestFetcher.getManifestLoadTimestamp() + MINIMUM_MANIFEST_REFRESH_PERIOD_MS)) {
      manifestFetcher.requestRefresh();
    }
  }

  @Override
  public final void getChunkOperation(List<? extends MediaChunk> queue, long seekPositionUs,
      long playbackPositionUs, ChunkOperationHolder out) {
    if (fatalError != null) {
      out.chunk = null;
      return;
    }

    evaluation.queueSize = queue.size();
    formatEvaluator.evaluate(queue, playbackPositionUs, formats, evaluation);
    Format selectedFormat = evaluation.format;
    out.queueSize = evaluation.queueSize;

    if (selectedFormat == null) {
      out.chunk = null;
      return;
    } else if (out.queueSize == queue.size() && out.chunk != null
        && out.chunk.format.equals(selectedFormat)) {
      // We already have a chunk, and the evaluation hasn't changed either the format or the size
      // of the queue. Leave unchanged.
      return;
    }

    // In all cases where we return before instantiating a new chunk, we want out.chunk to be null.
    out.chunk = null;

    StreamElement streamElement = getElement(currentManifest);
    if (streamElement.chunkCount == 0) {
      // The manifest is currently empty for this stream.
      finishedCurrentManifest = true;
      return;
    }

    int chunkIndex;
    if (queue.isEmpty()) {
      if (currentManifest.isLive) {
        seekPositionUs = getLiveSeekPosition();
      }
      chunkIndex = streamElement.getChunkIndex(seekPositionUs);
    } else {
      MediaChunk previous = queue.get(out.queueSize - 1);
      chunkIndex = previous.isLastChunk ? -1 : previous.chunkIndex + 1 - currentManifestChunkOffset;
    }

    if (currentManifest.isLive) {
      if (chunkIndex < 0) {
        // This is before the first chunk in the current manifest.
        fatalError = new BehindLiveWindowException();
        return;
      } else if (chunkIndex >= streamElement.chunkCount) {
        // This is beyond the last chunk in the current manifest.
        finishedCurrentManifest = true;
        return;
      } else if (chunkIndex == streamElement.chunkCount - 1) {
        // This is the last chunk in the current manifest. Mark the manifest as being finished,
        // but continue to return the final chunk.
        finishedCurrentManifest = true;
      }
    }

    if (chunkIndex == -1) {
      // We've reached the end of the stream.
      return;
    }

    boolean isLastChunk = !currentManifest.isLive && chunkIndex == streamElement.chunkCount - 1;
    long chunkStartTimeUs = streamElement.getStartTimeUs(chunkIndex);
    long chunkEndTimeUs = isLastChunk ? -1
        : chunkStartTimeUs + streamElement.getChunkDurationUs(chunkIndex);
    int currentAbsoluteChunkIndex = chunkIndex + currentManifestChunkOffset;

    int trackIndex = getTrackIndex(selectedFormat);
    Uri uri = streamElement.buildRequestUri(trackIndex, chunkIndex);
    Chunk mediaChunk = newMediaChunk(selectedFormat, uri, null, extractorWrappers.get(trackIndex),
        drmInitData, dataSource, currentAbsoluteChunkIndex, isLastChunk, chunkStartTimeUs,
        chunkEndTimeUs, evaluation.trigger, mediaFormats.get(trackIndex));
    out.chunk = mediaChunk;
  }

  @Override
  public IOException getError() {
    return fatalError != null ? fatalError
        : (manifestFetcher != null ? manifestFetcher.getError() : null);
  }

  @Override
  public void onChunkLoadCompleted(Chunk chunk) {
    // Do nothing.
  }

  @Override
  public void onChunkLoadError(Chunk chunk, Exception e) {
    // Do nothing.
  }

  /**
   * For live playbacks, determines the seek position that snaps playback to be
   * {@link #liveEdgeLatencyUs} behind the live edge of the current manifest
   *
   * @return The seek position in microseconds.
   */
  private long getLiveSeekPosition() {
    long liveEdgeTimestampUs = Long.MIN_VALUE;
    for (int i = 0; i < currentManifest.streamElements.length; i++) {
      StreamElement streamElement = currentManifest.streamElements[i];
      if (streamElement.chunkCount > 0) {
        long elementLiveEdgeTimestampUs =
            streamElement.getStartTimeUs(streamElement.chunkCount - 1)
            + streamElement.getChunkDurationUs(streamElement.chunkCount - 1);
        liveEdgeTimestampUs = Math.max(liveEdgeTimestampUs, elementLiveEdgeTimestampUs);
      }
    }
    return liveEdgeTimestampUs - liveEdgeLatencyUs;
  }

  private StreamElement getElement(SmoothStreamingManifest manifest) {
    return manifest.streamElements[streamElementIndex];
  }

  private int getTrackIndex(Format format) {
    TrackElement[] tracks = currentManifest.streamElements[streamElementIndex].tracks;
    for (int i = 0; i < tracks.length; i++) {
      if (tracks[i].format.equals(format)) {
        return i;
      }
    }
    // Should never happen.
    throw new IllegalStateException("Invalid format: " + format);
  }

  private static MediaFormat getMediaFormat(StreamElement streamElement, int trackIndex) {
    TrackElement trackElement = streamElement.tracks[trackIndex];
    Format trackFormat = trackElement.format;
    String mimeType = trackFormat.mimeType;
    if (streamElement.type == StreamElement.TYPE_VIDEO) {
      MediaFormat format = MediaFormat.createVideoFormat(mimeType, MediaFormat.NO_VALUE,
          trackFormat.width, trackFormat.height, Arrays.asList(trackElement.csd));
      format.setMaxVideoDimensions(streamElement.maxWidth, streamElement.maxHeight);
      return format;
    } else if (streamElement.type == StreamElement.TYPE_AUDIO) {
      List<byte[]> csd;
      if (trackElement.csd != null) {
        csd = Arrays.asList(trackElement.csd);
      } else {
        csd = Collections.singletonList(CodecSpecificDataUtil.buildAacAudioSpecificConfig(
            trackFormat.audioSamplingRate, trackFormat.numChannels));
      }
      MediaFormat format = MediaFormat.createAudioFormat(mimeType, MediaFormat.NO_VALUE,
          trackFormat.numChannels, trackFormat.audioSamplingRate, csd);
      return format;
    } else if (streamElement.type == StreamElement.TYPE_TEXT) {
      return MediaFormat.createTextFormat(trackFormat.mimeType);
    }
    return null;
  }

  private static MediaChunk newMediaChunk(Format formatInfo, Uri uri, String cacheKey,
      ChunkExtractorWrapper extractorWrapper, DrmInitData drmInitData, DataSource dataSource,
      int chunkIndex, boolean isLast, long chunkStartTimeUs, long chunkEndTimeUs,
      int trigger, MediaFormat mediaFormat) {
    long offset = 0;
    DataSpec dataSpec = new DataSpec(uri, offset, -1, cacheKey);
    // In SmoothStreaming each chunk contains sample timestamps relative to the start of the chunk.
    // To convert them the absolute timestamps, we need to set sampleOffsetUs to -chunkStartTimeUs.
    return new ContainerMediaChunk(dataSource, dataSpec, trigger, formatInfo, chunkStartTimeUs,
        chunkEndTimeUs, chunkIndex, isLast, chunkStartTimeUs, extractorWrapper, mediaFormat,
        drmInitData, true);
  }

  private static byte[] getKeyId(byte[] initData) {
    StringBuilder initDataStringBuilder = new StringBuilder();
    for (int i = 0; i < initData.length; i += 2) {
      initDataStringBuilder.append((char) initData[i]);
    }
    String initDataString = initDataStringBuilder.toString();
    String keyIdString = initDataString.substring(
        initDataString.indexOf("<KID>") + 5, initDataString.indexOf("</KID>"));
    byte[] keyId = Base64.decode(keyIdString, Base64.DEFAULT);
    swap(keyId, 0, 3);
    swap(keyId, 1, 2);
    swap(keyId, 4, 5);
    swap(keyId, 6, 7);
    return keyId;
  }

  private static void swap(byte[] data, int firstPosition, int secondPosition) {
    byte temp = data[firstPosition];
    data[firstPosition] = data[secondPosition];
    data[secondPosition] = temp;
  }

}
