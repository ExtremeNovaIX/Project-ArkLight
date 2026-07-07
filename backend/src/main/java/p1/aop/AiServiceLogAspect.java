package p1.aop;

import lombok.CustomLog;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Aspect
@Component
@CustomLog
public class AiServiceLogAspect {

    @Around("execution(* p1.component.ai.service.*.*(..))")
    public Object profile(ProceedingJoinPoint pjp) throws Throwable {
        String fullClassName = pjp.getSignature().getDeclaringTypeName();
        String className = fullClassName.substring(fullClassName.lastIndexOf(".") + 1);

        if (className.endsWith("AiService")) {
            className = className.substring(0, className.length() - "AiService".length());
        }

        String methodName = pjp.getSignature().getName();
        String serviceInfo = className + ":" + methodName;

        MDC.put("serviceInfo", serviceInfo);

        try {
            return pjp.proceed();
        } finally {
            MDC.remove("serviceInfo");
        }
    }
}