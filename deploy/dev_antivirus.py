"""Version the temporary signature-age exception for the ordinary HTTPS Dev only."""
from dev_payment_reserve import ORIGIN, canonical_origin

VARIABLE = "ANTIVIRUS_MAX_SIGNATURE_AGE"
DEFAULT_AGE = "PT72H"
DEV_AGE = "P7D"


def signature_age(project, local, values, release, historical_backup=False):
    if (
        project == "cabinet-dev" and not local and release is not None and not historical_backup
        and values.get("CABINET_DOMAIN") == "dev.credit-cabinet.ru"
        and canonical_origin(values.get("PUBLIC_CABINET_URL")) == ORIGIN
    ):
        return DEV_AGE
    # Ambient CI/shell variables must not carry the isolated CI exception into Prod.
    return values.get(VARIABLE, DEFAULT_AGE)
