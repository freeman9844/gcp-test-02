package com.example.dataflow;

import com.example.dataflow.transforms.SketchBasedHotKeyDetector;
import com.example.dataflow.utils.SyntheticDataGenerator;
import org.apache.beam.runners.dataflow.options.DataflowPipelineOptions;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.Duration;

/**
 * [Main Entry Point] Hot Key 로깅 샘플 파이프라인 (확률적 스케칭 기반)
 * 
 * 주요 특징:
 * - 확률적 Hot Key 감지: SketchFrequencies (Count-Min Sketch) 알고리즘 사용
 * - 메모리 최적화: 키의 양에 상관없이 고정된 메모리 부하로 Hot Key를 감지합니다.
 */
public class HotKeyLoggerPipeline {

    public interface Options extends DataflowPipelineOptions {
        @Description("윈도우 지속 시간 (초)")
        @Default.Integer(60)
        Integer getWindowDurationSeconds();

        void setWindowDurationSeconds(Integer value);

        @Description("Hot Key 판단을 위한 카운트 임게값")
        @Default.Long(100)
        Long getHotKeyThreshold();

        void setHotKeyThreshold(Long value);
    }

    public static void main(String[] args) {
        Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);
        Pipeline p = Pipeline.create(options);

        // 1. 가상 데이터 생성 (Skewed Data)
        PCollection<KV<String, String>> data = p.apply("GenerateSyntheticData", new SyntheticDataGenerator());

        // 2. 윈도우 설정
        PCollection<KV<String, String>> windowedData = data.apply("ApplyWindow",
                Window.into(FixedWindows.of(Duration.standardSeconds(options.getWindowDurationSeconds()))));

        /*
         * 3. 확률적 Hot Key 감지 (Sketching Sidecar)
         * - 정확한 합산 대신 확률적 추정을 사용하여 메모리 효율성을 극대화합니다.
         * - 사이드카 패턴으로 구현되어 메인 파이프라인과 독립적으로 동작합니다.
         */
        windowedData.apply("SketchSidecarDetection", new SketchBasedHotKeyDetector(
                options.getHotKeyThreshold(),
                0.01, // 1% relative error
                0.99 // 99% confidence
        ));

        p.run().waitUntilFinish();
    }
}
