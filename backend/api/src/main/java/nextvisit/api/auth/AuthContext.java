package nextvisit.api.auth;

import nextvisit.api.cases.CaseEntity;

/** 필터가 만들고 컨트롤러가 @CurrentGuardian으로 받는다. */
public record AuthContext(Guardian guardian, CaseEntity kase) {}
