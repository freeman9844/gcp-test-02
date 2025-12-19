package com.example.dataflow.transforms;

import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.extensions.sketching.SketchFrequencies;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Keys;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.PDone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * [Best Practice - Advanced] 확률적 데이터 구조(Sketching)를 이용한 Hot Key 감지
 * 
 * 설계 원칙 (사이드카 패턴):
 * - 확률적 데이터 구조(CMS): 고정 메모리(Fixed Memory)로 무한한 키 공간의 빈도를 추정합니다.
 * - 메인 로직 분리(Sidecar): Hot Key 감지 로직이 실패하거나 지연되어도 메인 데이터 처리에 지장을 주지 않습니다.
 */
public class SketchBasedHotKeyDetector extends PTransform<PCollection<KV<String, String>>, PDone> {

    private static final Logger LOG = LoggerFactory.getLogger(SketchBasedHotKeyDetector.class);
    private final long threshold;
    private final double epsilon;
    private final double confidence;

    public SketchBasedHotKeyDetector(long threshold, double epsilon, double confidence) {
        this.threshold = threshold;
        this.epsilon = epsilon;
        this.confidence = confidence;
    }

    @Override
    public PDone expand(PCollection<KV<String, String>> input) {

        // 1. 원본 데이터의 Coder 정보 추출 (빈도 추정 시 필요)
        final Coder<String> keyCoder = ((KvCoder<String, String>) input.getCoder()).getKeyCoder();

        // 샘플링 비율 (10%)
        final double sampleRate = 0.1;
        final long extrapolationFactor = (long) (1.0 / sampleRate);

        // [Sidecar Branch 1] Sketch (CMS) 생성
        // - [Best Practice] 무거운 트래픽 환경을 고려하여 10% 샘플링(Bernoulli Sampling)을 먼저 수행합니다.
        // - 샘플링된 데이터만 Sketch에 넣음으로써 워커의 CPU/메모리 부하를 추가로 절감합니다.
        PCollectionView<SketchFrequencies.Sketch<String>> sketchView = input
                .apply("Sample10Percent", org.apache.beam.sdk.transforms.Filter.by(e -> Math.random() < sampleRate))
                .apply("ExtractKeys", Keys.<String>create())
                .apply("BuildSketch",
                        org.apache.beam.sdk.transforms.Combine.<String, SketchFrequencies.Sketch<String>>globally(
                                SketchFrequencies.CountMinSketchFn.<String>create(keyCoder)
                                        .withAccuracy(epsilon, confidence))
                                .withoutDefaults())
                .apply("CreateSketchView", View.asSingleton());

        // [Sidecar Branch 2] Sketch를 기반으로 실제 Hot Key 로깅
        input.apply("MonitorHotKeys", ParDo.of(new DoFn<KV<String, String>, Void>() {

            @ProcessElement
            public void processElement(ProcessContext c) {
                // 현재 윈도우의 요약된 '샘플링된' Sketch 정보를 가져옵니다.
                SketchFrequencies.Sketch<String> sketch = c.sideInput(sketchView);
                String key = c.element().getKey();

                // 샘플링된 빈도를 추정하고, extrapolationFactor(10)를 곱하여 전체 빈도를 유추합니다.
                long estimatedSampledCount = sketch.estimateCount(key, keyCoder);
                long extrapolatedCount = estimatedSampledCount * extrapolationFactor;

                if (extrapolatedCount >= threshold) {
                    LOG.warn(
                            "[Sketch-Sampling-Sidecar] Detected Potential HOT KEY: [{}], Extrapolated Count: [{}] (Sampled: {})",
                            key, extrapolatedCount, estimatedSampledCount);
                }
            }
        }).withSideInputs(sketchView));

        return PDone.in(input.getPipeline());
    }
}
