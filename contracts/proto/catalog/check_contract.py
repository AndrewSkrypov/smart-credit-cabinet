"""Compile the Catalog v1 schema and verify the fixture invariants."""

import argparse
import copy
from pathlib import Path
import re
import subprocess
import tempfile

from google.protobuf import descriptor_pb2, descriptor_pool, json_format, message_factory


ROOT = Path(__file__).resolve().parent
PACKAGE = "creditcabinet.catalog.v1"


def compile_schema(protoc, include):
    with tempfile.TemporaryDirectory() as temp:
        output = Path(temp) / "catalog.pb"
        subprocess.run(
            [protoc, f"-I{ROOT}", f"-I{include}", "--include_imports",
             f"--descriptor_set_out={output}", "v1/catalog.proto"],
            check=True,
        )
        return descriptor_pb2.FileDescriptorSet.FromString(output.read_bytes())


def make_pool(descriptors):
    pool = descriptor_pool.DescriptorPool()
    for file in descriptors.file:
        pool.Add(file)
    return pool


def fixture(pool, message_name, filename):
    message = message_factory.GetMessageClass(pool.FindMessageTypeByName(f"{PACKAGE}.{message_name}"))
    return json_format.Parse((ROOT / "fixtures" / filename).read_text(), message())


def timestamp_key(timestamp):
    return timestamp.seconds, timestamp.nanos


def verify_product(product):
    assert product.HasField("ref") and re.fullmatch(r"[A-Z0-9_]{1,32}", product.ref.code)
    assert product.ref.version > 0
    assert 0 < len(product.name.strip()) <= 255 and 0 < len(product.description.strip()) <= 5000
    assert product.purposes and len(set(product.purposes)) == len(product.purposes)
    purpose_values = product.DESCRIPTOR.fields_by_name["purposes"].enum_type.values_by_number
    assert all(purpose in purpose_values and purpose != 0 for purpose in product.purposes)
    assert 0 < product.min_amount_rub <= product.auto_approval_limit_rub <= product.max_amount_rub
    assert 1 <= product.min_term_months <= product.max_term_months <= 120
    assert 1 <= product.base_rate_bps <= 10000
    assert product.HasField("agent_commission_rate_bps") and 0 <= product.agent_commission_rate_bps <= 1000
    status_values = product.DESCRIPTOR.fields_by_name["lifecycle_status"].enum_type.values_by_number
    assert product.lifecycle_status in status_values and product.lifecycle_status != 0
    if product.HasField("effective_from") and product.HasField("effective_to"):
        assert timestamp_key(product.effective_from) <= timestamp_key(product.effective_to)
    assert product.HasField("audit") and product.audit.HasField("created_at")
    assert 0 < len(product.audit.reason) <= 1000


def verify_snapshot(snapshot):
    assert snapshot.HasField("selected") and snapshot.HasField("evaluated_at")
    assert snapshot.products and len({product.ref.code for product in snapshot.products}) == len(snapshot.products)
    assert sum(product.ref == snapshot.selected for product in snapshot.products) == 1
    at = timestamp_key(snapshot.evaluated_at)
    for product in snapshot.products:
        verify_product(product)
        active = product.DESCRIPTOR.fields_by_name["lifecycle_status"].enum_type.values_by_name[
            "PRODUCT_LIFECYCLE_STATUS_ACTIVE"
        ].number
        assert product.lifecycle_status == active
        assert not product.HasField("effective_from") or timestamp_key(product.effective_from) <= at
        assert not product.HasField("effective_to") or timestamp_key(product.effective_to) >= at


def verify_negative(snapshot, mutate):
    invalid = copy.deepcopy(snapshot)
    mutate(invalid)
    try:
        verify_snapshot(invalid)
    except AssertionError:
        return
    raise AssertionError("Invalid catalogue snapshot passed validation")


def verify_additive_wire_change(descriptors, snapshot):
    amended = copy.deepcopy(descriptors)
    contract = next(file for file in amended.file if file.package == PACKAGE)
    product = next(message for message in contract.message_type if message.name == "ProductVersion")
    product.field.add(name="marketing_label", number=30, type=descriptor_pb2.FieldDescriptorProto.TYPE_STRING)
    amended_pool = make_pool(amended)
    descriptor = amended_pool.FindMessageTypeByName(f"{PACKAGE}.GetSubmissionSnapshotResponse")
    next_type = message_factory.GetMessageClass(descriptor)
    new_message = next_type.FromString(snapshot.SerializeToString())
    new_message.products[0].marketing_label = "Сезонный продукт"
    old_message = type(snapshot).FromString(new_message.SerializeToString())
    verify_snapshot(old_message)
    assert old_message.products[0].ref == snapshot.products[0].ref
    assert next_type.FromString(old_message.SerializeToString()).products[0].marketing_label == "Сезонный продукт"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--protoc", default="protoc")
    parser.add_argument("--include", required=True, help="Directory containing google/protobuf/timestamp.proto")
    args = parser.parse_args()
    descriptors = compile_schema(args.protoc, args.include)
    pool = make_pool(descriptors)
    service = pool.FindServiceByName(f"{PACKAGE}.CatalogService")
    rpc_types = {
        "GetSubmissionSnapshot": "GetSubmissionSnapshotResponse",
        "GetProductVersion": "GetProductVersionResponse",
        "ListCurrentProducts": "ListProductsResponse",
        "ListProductHistory": "ListProductsResponse",
        "CreateProduct": "GetProductVersionResponse",
        "ReplaceProduct": "GetProductVersionResponse",
    }
    for name, response in rpc_types.items():
        method = service.FindMethodByName(name)
        assert method.input_type.full_name == f"{PACKAGE}.{name}Request"
        assert method.output_type.full_name == f"{PACKAGE}.{response}"
        assert not method.client_streaming and not method.server_streaming

    snapshot = fixture(pool, "GetSubmissionSnapshotResponse", "submission-snapshot.json")
    verify_snapshot(snapshot)
    selected, alternative = snapshot.products
    assert selected.ref == snapshot.selected and selected.agent_commission_rate_bps == 175
    assert 2_000_000 * selected.agent_commission_rate_bps // 10_000 == 35_000
    assert alternative.ref.code == "INVESTMENT" and alternative.HasField("agent_commission_rate_bps")
    assert alternative.agent_commission_rate_bps == 0
    assert timestamp_key(snapshot.evaluated_at) == timestamp_key(selected.effective_from)
    at_end = copy.deepcopy(snapshot)
    at_end.evaluated_at.CopyFrom(selected.effective_to)
    verify_snapshot(at_end)
    verify_negative(at_end, lambda invalid: setattr(invalid.evaluated_at, "nanos", 1000))
    verify_negative(snapshot, lambda invalid: invalid.products[1].ClearField("agent_commission_rate_bps"))
    verify_negative(snapshot, lambda invalid: setattr(invalid.products[1].ref, "code", selected.ref.code))
    verify_negative(snapshot, lambda invalid: setattr(invalid.selected, "version", snapshot.selected.version + 1))
    verify_negative(snapshot, lambda invalid: setattr(invalid.products[0], "lifecycle_status", 3))

    historical = fixture(pool, "GetProductVersionResponse", "historical-product.json")
    verify_product(historical.product)
    assert historical.product == selected
    verify_additive_wire_change(descriptors, snapshot)
    print("Catalog v1: verified six RPCs, effective bounds, commission, audit and additive wire compatibility")


if __name__ == "__main__":
    main()
