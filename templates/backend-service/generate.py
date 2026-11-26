"""Generate a backend module in the checkout containing this template."""
import argparse
import re
import shutil
from pathlib import Path
from tempfile import TemporaryDirectory

TEMPLATE = Path(__file__).resolve().parent
ROOT = TEMPLATE.parents[1]


def generate(name):
    if re.fullmatch(r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*", name) is None:
        raise ValueError("Имя сервиса должно состоять из строчных букв, цифр и дефисов")

    module = f'":services:{name}"'
    target = ROOT / "services" / name
    settings = ROOT / "settings.gradle.kts"
    original_settings = settings.read_text(encoding="utf-8")
    if target.exists() or module in original_settings:
        raise ValueError(f"Модуль services/{name} уже существует")

    class_prefix = "".join(part.capitalize() for part in name.split("-"))
    renamed_sources = {
        "src/main/kotlin/ru/creditcabinet/ServiceApplication.kt": ("ServiceApplication", f"{class_prefix}Application"),
        "src/test/kotlin/ru/creditcabinet/ServiceStartsTest.kt": ("ServiceStartsTest", f"{class_prefix}StartsTest"),
    }
    with TemporaryDirectory(dir=target.parent) as workspace:
        staged = Path(workspace) / name
        staged.mkdir()
        for file in ("build.gradle.kts", "gradle.lockfile"):
            shutil.copyfile(TEMPLATE / file, staged / file)
        shutil.copytree(TEMPLATE / "src", staged / "src")
        for relative, (old, new) in renamed_sources.items():
            path = staged / relative
            content = path.read_text(encoding="utf-8").replace(old, new)
            path.unlink()
            path.with_name(f"{new}.kt").write_text(content, encoding="utf-8")
        updated_settings = staged.parent / settings.name
        updated_settings.write_text(original_settings.rstrip() + f"\n\ninclude({module})\n", encoding="utf-8")
        shutil.copymode(settings, updated_settings)
        staged.rename(target)
        try:
            updated_settings.replace(settings)
        except OSError:
            target.rename(staged)
            raise

    return target


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("service")
    args = parser.parse_args()
    try:
        print(generate(args.service))
    except ValueError as error:
        parser.error(str(error))
