package com.interviewer.domain.resume;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.interviewer.core.Text;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class JobDescription {

    private String sourceName = "";
    private String rawText = "";
    private String company = "";
    private String title = "";
    private List<String> mustHave = new ArrayList<>();
    private List<String> niceToHave = new ArrayList<>();
    private List<String> responsibilities = new ArrayList<>();

    @JsonIgnore
    public boolean isEmpty() {
        return rawText.isBlank();
    }

    @JsonIgnore
    public String compact(int limit) {
        List<String> parts = new ArrayList<>();
        if (!company.isEmpty() || !title.isEmpty()) {
            parts.add(("目标岗位：" + company + " " + title).strip());
        }
        if (!mustHave.isEmpty()) {
            parts.add("硬性要求：" + String.join("、", head(mustHave, 16)));
        }
        if (!niceToHave.isEmpty()) {
            parts.add("加分项：" + String.join("、", head(niceToHave, 12)));
        }
        if (!responsibilities.isEmpty()) {
            parts.add("职责：" + String.join("；", head(responsibilities, 8)));
        }
        return Text.cut(String.join("\n", parts), limit);
    }

    @JsonIgnore
    public String compact() {
        return compact(1200);
    }

    private static List<String> head(List<String> list, int limit) {
        return list.size() <= limit ? list : list.subList(0, limit);
    }
}
