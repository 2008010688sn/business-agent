#!/usr/bin/env python3
"""Bulk sanitization for the open-source backend copy."""
from __future__ import annotations

import re
import shutil
from pathlib import Path

ROOT = Path(r"D:\workspace\business-agent\backend")
SRC = ROOT / "src"

REWRITE_EXCLUDE = {
    "AiApplication.java",
    "DataAgentAsyncContextBridge.java",
    "DataAgentAsyncContextBridgeTest.java",
    "PrincipalProvisioningServiceImpl.java",
    "PrincipalProvisioningServiceImplTest.java",
    "EmployeePrincipalServiceImpl.java",
    "EmployeePrincipalService.java",
    "EmployeePrincipalServiceImplTest.java",
    "CachingEmployeeExecutionContextClient.java",
    "CachingEmployeeExecutionContextClientTest.java",
    "DelegatedAuthContextService.java",
    "DelegatedAuthContextServiceTest.java",
    "FlowManagerApprovalIdentityRestorer.java",
    "DataAgentVisibilityServiceImpl.java",
    "DataAgentVisibilityService.java",
    "DataAgentVisibilityServiceImplTest.java",
    "LegacyVisibilityPolicyProviderTest.java",
    "AgentUsageLimitService.java",
    "AgentUsageLimitServiceTest.java",
    "DataAgent.java",
    "DataAgentServiceImpl.java",
    "DataDataAgentServiceImplTest.java",
    "AgentModelConfigServiceImpl.java",
    "DingTalkStreamLifecycleService.java",
    "SuiteFileService.java",
    "SuiteFileServiceTest.java",
    "DigitalEmployeeController.java",
    "DataAgentMcpHeaderProvider.java",
    "ImApprovalCommandService.java",
    "ControllerAuthorizationBaselineTest.java",
    "DataAgentControllerPermissionTest.java",
    "NonHttpEntryAuthorizationBaselineTest.java",
    "DigitalEmployeeControllerPermissionTest.java",
    "AgentMemoryControllerExportGuardTest.java",
    "RouteProfileControllerTest.java",
    "RoutePreviewControllerTest.java",
    "SessionEventControllerTest.java",
    "SkillControllerTest.java",
}

DELETE_FILES = [
    SRC / "main/java/com/sn68/agent/dataagent/mq/AgentVisibilityWorkflowListener.java",
    SRC / "main/java/com/sn68/agent/dataagent/service/visibility/WorkflowAgentVisibilityApprovalAdapter.java",
    SRC / "main/java/com/sn68/agent/dataagent/task/mq/AgentTaskEventListener.java",
    SRC / "main/java/com/sn68/agent/dataagent/feign/DataAgentOutboundFeignInterceptor.java",
    SRC / "test/java/com/sn68/agent/dataagent/feign/DataAgentOutboundFeignInterceptorTest.java",
    SRC / "main/java/com/sn68/agent/dataagent/task/schedule/TaskSnailClusterJobRegistrar.java",
    SRC / "test/java/com/sn68/agent/dataagent/task/schedule/TaskSnailClusterJobRegistrarTest.java",
    SRC / "main/java/com/sn68/agent/dataagent/task/schedule/SnailJobOpenApiClusterJobOperations.java",
    SRC / "main/java/com/sn68/agent/dataagent/task/schedule/SnailClusterJobOperations.java",
    SRC / "main/java/com/sn68/agent/config/AiModelProperties.java",
    ROOT / "src/main/resources/db/ai_agent.sql",
    SRC / "main/java/com/sn68/agent/AiApplication.java",
    SRC / "main/java/com/sn68/agent/dataagent/service/file/SuiteFileService.java",
]

IMPORT_REPLACEMENTS = [
    (
        "com.xx.cloud.suite.feign.domain.resp.FilePreviewResp",
        "com.sn68.agent.dataagent.service.file.FilePreviewResp",
    ),
    (
        "com.xx.cloud.iam.feign.domain.req.DelegatedAuthContextReq",
        "com.sn68.agent.dataagent.iam.dto.DelegatedAuthContextReq",
    ),
    (
        "com.xx.cloud.iam.feign.domain.resp.DelegatedAuthContextResp",
        "com.sn68.agent.dataagent.iam.dto.DelegatedAuthContextResp",
    ),
    (
        "com.xx.cloud.iam.feign.domain.req.ServicePrincipalRolesReplaceReq",
        "com.sn68.agent.dataagent.iam.dto.ServicePrincipalRolesReplaceReq",
    ),
    (
        "com.xx.cloud.iam.feign.domain.req.ServicePrincipalStatusReq",
        "com.sn68.agent.dataagent.iam.dto.ServicePrincipalStatusReq",
    ),
    (
        "com.xx.cloud.iam.feign.domain.resp.ServicePrincipalAuthSnapshotResp",
        "com.sn68.agent.dataagent.iam.dto.ServicePrincipalAuthSnapshotResp",
    ),
    (
        "com.xx.cloud.iam.feign.domain.resp.ServicePrincipalRoleResp",
        "com.sn68.agent.dataagent.iam.dto.ServicePrincipalRoleResp",
    ),
    (
        "com.sn68.agent.dataagent.service.file.SuiteFileService",
        "com.sn68.agent.dataagent.service.file.LocalFileService",
    ),
]

DROP_IMPORT_PREFIXES = (
    "import cn.dev33.satoken.",
    "import com.aizuda.snailjob.",
    "import com.xx.cloud.",
    "import org.apache.rocketmq.",
)

FIXTURE_REPLACEMENTS = [
    ("xx-cloud-zeus.demandCreateExecute", "demo.echo.execute"),
    ("xx-cloud-zeus.demandCreateLatest", "demo.echo.latest"),
    ("xx-cloud-trove.demandProjectOptions", "demo.echo.projectOptions"),
    ("xx-cloud-trove.demandCustomerOptions", "demo.echo.customerOptions"),
    ("xx-cloud-trove.customerOptions", "demo.echo.customerOptions"),
    ("xx-cloud-zeus", "demo-echo"),
    ("xx-cloud-trove", "demo-echo"),
    ("zeus.demandCreateExecute", "demo.echo.execute"),
    ("zeus.demandCreateValidate", "demo.echo.validate"),
    ("zeus.demandCreateLatest", "demo.echo.latest"),
    ("zeus.demandCreate", "demo.echo"),
    ("zeus.query", "demo.echo.query"),
    ("zeus.write", "demo.echo.write"),
    ("zeus.order.close", "demo.echo.close"),
    ("demandCreateExecute", "echoExecute"),
    ("demandCreateLatest", "echoLatest"),
    ("demandCreateValidate", "echoValidate"),
    ("demandCreate", "echo"),
    ("demandProjectOptions", "projectOptions"),
    ("demandCustomerOptions", "customerOptions"),
    ("demand.create", "echo.create"),
]


def drop_import_lines(text: str) -> str:
    lines = text.splitlines(keepends=True)
    kept = []
    for line in lines:
        stripped = line.lstrip()
        if any(stripped.startswith(prefix) for prefix in DROP_IMPORT_PREFIXES):
            continue
        kept.append(line)
    return "".join(kept)


def strip_annotations(text: str) -> str:
    text = re.sub(r"[ \t]*@SaCheckPermission\([^)]*\)\r?\n", "", text)
    text = re.sub(r"[ \t]*@JobExecutor\([^)]*\)\r?\n", "", text)
    text = re.sub(r"[ \t]*@RocketMQMessageListener\([^)]*\)\r?\n", "", text)
    text = re.sub(r"[ \t]*@EnableSnailJob\r?\n", "", text)
    text = re.sub(r"[ \t]*@EnableOAuth2Client\r?\n", "", text)
    text = re.sub(r"[ \t]*@EnableDiscoveryClient\r?\n", "", text)
    text = re.sub(r"[ \t]*@EnableFeignClients\([^)]*\)\r?\n", "", text)
    text = re.sub(r"[ \t]*@RemoteResult\r?\n", "", text)
    return text


def apply_replacements(text: str, test_file: bool) -> str:
    for old, new in IMPORT_REPLACEMENTS:
        text = text.replace(old, new)
    text = text.replace("SuiteFileService", "LocalFileService")
    text = text.replace("suiteFileService", "localFileService")
    if test_file:
        for old, new in FIXTURE_REPLACEMENTS:
            text = text.replace(old, new)
        text = text.replace('"zeus"', '"demo-echo"')
        text = text.replace("RouteCandidate echo", "RouteCandidate echoCandidate")
        # restore accidental variable collision from demandCreate -> echo
        text = text.replace("new RouteCandidate(\n", "new RouteCandidate(\n")
    return text


def process_java(path: Path) -> None:
    if path.name in REWRITE_EXCLUDE:
        return
    original = path.read_text(encoding="utf-8")
    text = original
    text = drop_import_lines(text)
    text = strip_annotations(text)
    text = apply_replacements(text, "src/test" in str(path).replace("\\", "/"))
    # collapse extra blank lines after import stripping
    text = re.sub(r"\n{3,}", "\n\n", text)
    if text != original:
        path.write_text(text, encoding="utf-8")


def delete_empty_xx() -> None:
    for base in [
        SRC / "main/java/com/xx",
        SRC / "test/java/com/xx",
    ]:
        if base.exists():
            shutil.rmtree(base, ignore_errors=True)


def main() -> None:
    for path in SRC.rglob("*.java"):
        process_java(path)
    for path in DELETE_FILES:
        if path.exists():
            path.unlink()
    delete_empty_xx()
    print("sanitize_backend.py done")


if __name__ == "__main__":
    main()
