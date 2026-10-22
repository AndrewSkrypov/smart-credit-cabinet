#!/usr/bin/env python3
"""Stop the explicitly selected production project without deleting volumes."""
import argparse
from common import Deployment

parser = argparse.ArgumentParser(description="Остановить выбранный prod-проект, сохранив БД, документы и сертификаты")
parser.add_argument("--project", default="cabinet-prod")
parser.add_argument("--env-file", default="deploy/.env")
parser.add_argument("--local", action="store_true")
args = parser.parse_args()
Deployment(args.project, args.env_file, args.local).compose("stop")
