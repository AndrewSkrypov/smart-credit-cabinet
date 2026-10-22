#!/usr/bin/env python3
"""Build the selected release without starting application writers before a restore."""
import argparse
from common import Deployment

parser = argparse.ArgumentParser(description="Собрать образы указанного выпуска без запуска приложений")
parser.add_argument("--project", default="cabinet-prod")
parser.add_argument("--env-file", default="deploy/.env")
parser.add_argument("--local", action="store_true")
args = parser.parse_args()
deployment = Deployment(args.project, args.env_file, args.local)
deployment.assert_build_revision()
deployment.compose("build")
