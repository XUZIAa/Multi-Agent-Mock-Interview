package com.interviewer.rpc.dto;

import com.interviewer.core.Text;
import jakarta.validation.constraints.Size;

/** 出编码题。skill 留空则由模型按岗位自行选考察方向。 */
public record ComposeChallengeBody(@Size(max = 60) String skill) {

    public ComposeChallengeBody {
        skill = Text.safe(skill);
    }
}
