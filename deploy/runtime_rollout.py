"""Order the isolated Dev hooks while ingress and the previous Credit are stopped."""
from assessment_dev import SERVICES as ASSESSMENT_SERVICES, read_record
from catalog_rollout import rollout as transfer_catalog, verify_runtime

MIGRATIONS = ("db-init", "identity-migrate", "credit-migrate", "engagement-migrate")
RUNTIME_SERVICES = {
    "catalog": ("catalog-grpc",),
    "assessment": ASSESSMENT_SERVICES,
    "servicing": ("servicing", "servicing-db"),
    "documents": ("documents", "documents-db"),
}
RUNTIME_WRITERS = {
    "catalog": "catalog-grpc", "assessment": "assessment", "servicing": "servicing", "documents": "documents",
}


def runtime_services(deployment):
    return tuple(service for name in deployment.runtime_modes for service in RUNTIME_SERVICES[name])


def stop_writers(deployment):
    deployment.compose("stop", "caddy")
    deployment.compose("stop", "-t", "150", "credit", "identity", "engagement",
                       *(RUNTIME_WRITERS[name] for name in deployment.runtime_modes),
                       *(["analytics"] if deployment.alpha_dev else []))


def prepare_credit(deployment, arguments, *, storage_ready=False):
    if not storage_ready:
        deployment.compose(*arguments, "postgres")
    for service in MIGRATIONS:
        deployment.compose("run", "--rm", "--no-deps", service)


def prepare_private(deployment, arguments):
    if deployment.catalog_enabled:
        transfer_catalog(deployment)
        deployment.environment["CABINET_CATALOG_MODE"] = deployment.catalog_mode
    assessment_receipt = None
    if deployment.assessment:
        assessment_receipt = deployment.assessment.prepare()
        deployment.environment.update(deployment.assessment.credit_environment(assessment_receipt))
    if deployment.servicing:
        deployment.servicing.prepare(deployment, arguments)
        deployment.environment.update(deployment.servicing.switch(deployment))
    if deployment.documents:
        deployment.documents.prepare(deployment, arguments)
    return assessment_receipt


def verify_private(deployment, assessment_receipt=None):
    reports = {}
    if deployment.catalog_enabled:
        reports["catalog"] = verify_runtime(deployment)
    if deployment.assessment:
        if assessment_receipt is None:
            assessment_receipt = read_record(deployment.assessment.state / "active.json")
            if assessment_receipt is None:
                raise RuntimeError("Не найдена активная квитанция Assessment")
        deployment.assessment.verify_containers({
            "assessment": deployment.release.local_image_id("assessment"),
            "assessment-kafka": deployment.release.local_image_id("kafka"),
        })
        deployment.assessment.verify_credit(assessment_receipt)
        reports["assessment"] = assessment_receipt
    if deployment.servicing:
        reports["servicing"] = deployment.servicing.verify_credit(deployment)
    if deployment.documents:
        reports["documents"] = deployment.documents.verify_credit(deployment)
    return reports
