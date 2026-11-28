"""Select the versioned reserve simulation overlay; never enable it from ENV."""

FLAG = "DEV_PAYMENT_RESERVE_SIMULATION_ENABLED"
CONTEXT = "CABINET_DEPLOYMENT_PROJECT"
OVERLAY = "deploy/compose.dev-payment-reserve.yaml"
ORIGIN = "https://dev.credit-cabinet.ru"


def canonical_origin(value):
    return ORIGIN if isinstance(value, str) and value in {ORIGIN, ORIGIN + "/"} else None


def validate_overrides(values, environ):
    for source in (values, environ):
        requested = source.get(FLAG, "false")
        if requested not in {"false", "true"}:
            raise ValueError("DEV_PAYMENT_RESERVE_SIMULATION_ENABLED должен быть true или false")
        if requested == "true":
            raise ValueError("Симуляция резерва включается только версионированным конфигом cabinet-dev, не ENV/CI")
        if source.get(CONTEXT, ""):
            raise ValueError("CABINET_DEPLOYMENT_PROJECT задаётся контроллером поставки, не ENV/CI")


def enabled(project, local, values, release, historical_backup=False):
    return (
        project == "cabinet-dev" and not local and release is not None and not historical_backup
        and values.get("CABINET_DOMAIN") == "dev.credit-cabinet.ru"
        and canonical_origin(values.get("PUBLIC_CABINET_URL")) == ORIGIN
    )
