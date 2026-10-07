package com.evops.exception;

import com.evops.entity.RoutePublishCheck;

/**
 * 发布前校验（临时禁飞区/风速/电量）未通过。
 * 校验记录已落库，随异常返回以保证航线规划可追溯。
 */
public class RouteValidationException extends IllegalArgumentException {

    private final transient RoutePublishCheck check;

    public RouteValidationException(String message, RoutePublishCheck check) {
        super(message);
        this.check = check;
    }

    public RoutePublishCheck getCheck() {
        return check;
    }
}
