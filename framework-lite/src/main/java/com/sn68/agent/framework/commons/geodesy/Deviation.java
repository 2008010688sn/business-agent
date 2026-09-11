package com.sn68.agent.framework.commons.geodesy;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * @author xujp
 */
@Getter
@RequiredArgsConstructor
public enum Deviation {

    MIN(0.1d),

    BASE(1.0d),
    ;
    private final double degree;

}
