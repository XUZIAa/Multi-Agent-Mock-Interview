package com.interviewer.rpc;

import com.interviewer.core.type.ScoreDimension;
import com.interviewer.data.dto.StoredMistake;
import com.interviewer.data.dto.TrendPoint;
import com.interviewer.data.repository.ReviewRepository;
import com.interviewer.rpc.dto.MistakeCounts;
import com.interviewer.rpc.dto.Ok;
import com.interviewer.rpc.dto.SetMasteredBody;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Tag(name = "reviews")
public class MistakesController {

    private final ReviewRepository reviews;

    public MistakesController(ReviewRepository reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/mistakes")
    public List<StoredMistake> list(
            // 查询串沿用 snake_case，前端按这个名字拼参数
            @RequestParam(name = "include_mastered", defaultValue = "false")
            boolean includeMastered,
            @RequestParam(defaultValue = "") String topic,
            @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit) {
        return reviews.listMistakes(includeMastered, topic, limit);
    }

    @GetMapping("/mistakes/counts")
    public MistakeCounts counts() {
        int[] counts = reviews.mistakeCounts();
        return new MistakeCounts(counts[0], counts[1]);
    }

    @GetMapping("/mistakes/topics")
    public List<String> topics() {
        return reviews.topics();
    }

    @PostMapping("/mistakes/mastered")
    public Ok setMastered(@Valid @RequestBody SetMasteredBody body) {
        reviews.setMastered(body.mistakeId(), body.mastered());
        return Ok.DONE;
    }

    @DeleteMapping("/mistakes/{mistake_id}")
    public Ok delete(@PathVariable("mistake_id") int mistakeId) {
        reviews.deleteMistake(mistakeId);
        return Ok.DONE;
    }

    @GetMapping("/trends/overall")
    public List<TrendPoint> overall(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return reviews.overallSeries(limit);
    }

    @GetMapping("/trends/dimensions")
    public Map<ScoreDimension, List<TrendPoint>> dimensions(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return reviews.dimensionSeries(limit);
    }
}
