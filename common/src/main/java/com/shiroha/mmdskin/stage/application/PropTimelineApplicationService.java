package com.shiroha.mmdskin.stage.application;

import com.shiroha.mmdskin.asset.catalog.HeldPmxAssets;
import com.shiroha.mmdskin.asset.catalog.HeldPmxMotionFiles;
import com.shiroha.mmdskin.asset.catalog.PropTimelineCalibration;
import com.shiroha.mmdskin.asset.catalog.PropTimelineFiles;
import com.shiroha.mmdskin.asset.catalog.TimelineMotionChoices;
import com.shiroha.mmdskin.config.ModelConfigData;
import com.shiroha.mmdskin.config.PathConstants;
import com.shiroha.mmdskin.stage.domain.timeline.PropTimeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Owns prop-timeline file, calibration, conflict, and compensation workflows for the editor UI. */
public final class PropTimelineApplicationService {
    @FunctionalInterface
    public interface CalibrationCapture {
        PropTimelineCalibration.Signature capture(Path motion, Path reference) throws IOException;
    }

    @FunctionalInterface
    public interface PlaybackCalibrationCapture {
        PropTimelineCalibration.Signature capture(Path motion) throws IOException;
    }

    public record CalibrationResult(PropTimelineCalibration.Signature signature,
                                    String conflict, boolean referenceMissing) {
        public boolean valid() {
            return conflict == null;
        }
    }

    public record OpenResult(Path motion, PropTimeline timeline, Map<String, Path> motionChoices,
                             String referenceLabel,
                             Path referenceMotion, boolean compensationStale) {
    }

    public record SaveResult(PropTimeline timeline, boolean recalculated) {
    }

    public record PlaybackResult(PropTimeline timeline, String warning) {
    }

    private final String modelName;
    private final ModelConfigData config;
    private final Path modelDir;
    private final Path stageDir;
    private final CalibrationCapture capture;
    private PropTimelineFiles.Document currentDocument;

    public PropTimelineApplicationService(String modelName, ModelConfigData config) {
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.config = Objects.requireNonNull(config, "config").normalizedCopy();
        this.modelDir = PathConstants.getModelDir(modelName).toPath();
        this.stageDir = PathConstants.getStageAnimDir().toPath();
        this.capture = (motion, reference) ->
                PropTimelineCalibration.capture(modelName, this.config, motion, reference);
    }

    PropTimelineApplicationService(Path modelDir, Path stageDir, CalibrationCapture capture) {
        this.modelName = null;
        this.config = null;
        this.modelDir = Objects.requireNonNull(modelDir, "modelDir");
        this.stageDir = Objects.requireNonNull(stageDir, "stageDir");
        this.capture = Objects.requireNonNull(capture, "capture");
    }

    public OpenResult open() throws IOException {
        if (config == null) throw new IllegalStateException("Configured timeline service required");
        Path motion = HeldPmxMotionFiles.resolve(HeldPmxAssets.motionDirectory(modelName, config.heldPmxFile),
                config.heldPmxStageMotion);
        if (motion == null || config.heldPmxFile.isEmpty()) {
            throw new IllegalArgumentException("Select character and prop assets before editing a timeline");
        }
        return open(motion, HeldPmxAssets.fileName(config.heldPmxFile));
    }

    public OpenResult open(Path motion, String pmxFile) throws IOException {
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(pmxFile, "pmxFile");
        PropTimelineFiles.Document document = readDocument(motion, pmxFile);
        currentDocument = document;
        Map<String, Path> motionChoices = TimelineMotionChoices.list(modelDir, stageDir);
        String referenceLabel = PropTimelineFiles.referenceMotion(document);
        Path referenceMotion = motionChoices.get(referenceLabel);
        CalibrationResult calibration = calibrate(
                document, document.timeline(), referenceLabel, referenceMotion);
        return new OpenResult(motion, document.timeline(), motionChoices,
                referenceLabel, referenceMotion, !calibration.valid());
    }

    public CalibrationResult calibrate(PropTimelineFiles.Document document, PropTimeline timeline,
                                       String referenceLabel, Path referenceMotion) throws IOException {
        if (timeline.boneKeys().isEmpty()) return new CalibrationResult(null, null, false);
        boolean missing = referenceMissing(referenceLabel, referenceMotion);
        if (missing) {
            return new CalibrationResult(null,
                    "Reference motion is missing: " + referenceLabel, true);
        }
        PropTimelineCalibration.Signature current = capture.capture(document.motion(), referenceMotion);
        List<Path> actorMotions = referenceMotion == null ? List.of() : List.of(referenceMotion);
        String conflict = calibrationConflict(document, timeline, current, actorMotions);
        return new CalibrationResult(current, conflict, false);
    }

    public PropTimeline recalculate(PropTimeline source, PropRegistrationService.Registrar registrar) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(registrar, "registrar");
        var keys = new ArrayList<PropTimeline.Key<PropTimeline.BoneBinding>>();
        for (var key : source.boneKeys()) {
            var binding = key.value();
            if (binding.unbind()) {
                keys.add(key);
                continue;
            }
            var baseline = new PropTimeline(
                    source.initial(), source.visibility(), source.attachments(), keys);
            keys.add(new PropTimeline.Key<>(key.frame(), registrar.register(
                    key.frame(), baseline, binding.childBone(), binding.bone(), binding.offset(),
                    binding.keepWorld(), binding.placement())));
        }
        return new PropTimeline(source.initial(), source.visibility(), source.attachments(), keys);
    }

    public SaveResult save(PropTimeline timeline, String referenceLabel, Path referenceMotion,
                           boolean compensationStale,
                           PropRegistrationService.Registrar registrar) throws IOException {
        Objects.requireNonNull(timeline, "timeline");
        PropTimelineFiles.Document document = requireDocument();
        CalibrationResult calibration = calibrate(document, timeline, referenceLabel, referenceMotion);
        if (calibration.referenceMissing()) {
            throw new IllegalStateException("Reference motion is missing: " + referenceLabel);
        }
        boolean recalculated = !timeline.boneKeys().isEmpty()
                && (compensationStale || !calibration.valid());
        PropTimeline savedTimeline = recalculated
                ? recalculate(timeline, Objects.requireNonNull(registrar, "registrar"))
                : timeline;
        PropTimelineFiles.Document saved = PropTimelineFiles.save(
                document, savedTimeline, referenceLabel, calibration.signature());
        currentDocument = saved;
        return new SaveResult(savedTimeline, recalculated);
    }

    public boolean referenceMissing(String referenceLabel, Path referenceMotion) {
        return referenceLabel != null && !referenceLabel.isEmpty()
                && (referenceMotion == null || !Files.isRegularFile(referenceMotion));
    }

    public boolean isOpen() {
        return currentDocument != null;
    }

    public boolean isNewDocument() {
        return currentDocument != null && currentDocument.originalJson() == null;
    }

    /**
     * Loads a stage timeline while keeping the prop VMD usable when optional bone parenting cannot be trusted.
     */
    public static PlaybackResult loadForPlayback(Path motion, String pmxFile,
                                                 PlaybackCalibrationCapture currentSignature,
                                                 List<Path> actorMotions) {
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(pmxFile, "pmxFile");
        List<Path> motions = actorMotions == null ? List.of() : List.copyOf(actorMotions);
        PropTimeline timeline = PropTimeline.EMPTY;
        try {
            PropTimelineFiles.Document document = readDocument(motion, pmxFile);
            timeline = document.timeline();
            if (!timeline.boneKeys().isEmpty()) {
                PropTimelineCalibration.Signature current =
                        currentSignature == null ? null : currentSignature.capture(motion);
                String mismatch = calibrationConflict(document, timeline, current, motions);
                if (mismatch != null) {
                    return new PlaybackResult(withoutBoneParents(timeline),
                            mismatch + "; recalculate bone parenting in the timeline editor. "
                                    + "Prop VMD and other tracks remain active");
                }
            }
            return new PlaybackResult(timeline, null);
        } catch (IOException failure) {
            return new PlaybackResult(withoutBoneParents(timeline),
                    failure.getMessage() + "; prop VMD remains active");
        } catch (RuntimeException failure) {
            return new PlaybackResult(withoutBoneParents(timeline),
                    "Invalid timeline calibration: " + failure.getMessage() + "; prop VMD remains active");
        }
    }

    private static PropTimelineFiles.Document readDocument(Path motion, String pmxFile) throws IOException {
        return PropTimelineFiles.open(motion, pmxFile);
    }

    private PropTimelineFiles.Document requireDocument() {
        if (currentDocument == null) throw new IllegalStateException("Open a timeline before saving");
        return currentDocument;
    }

    private static String calibrationConflict(PropTimelineFiles.Document document, PropTimeline timeline,
                                              PropTimelineCalibration.Signature current,
                                              List<Path> actorMotions) throws IOException {
        return PropTimelineCalibration.mismatch(
                PropTimelineCalibration.read(document), current, actorMotions, timeline);
    }

    private static PropTimeline withoutBoneParents(PropTimeline timeline) {
        var initial = timeline.initial();
        return new PropTimeline(
                new PropTimeline.State(initial.visible(), initial.attachment()),
                timeline.visibility(), timeline.attachments());
    }
}
