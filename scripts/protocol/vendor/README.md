# Vendored protocol data

`bedrock-1.26.45-proto.yml` is copied verbatim from PrismarineJS/minecraft-data
(`data/bedrock/1.26.45/proto.yml`), MIT licensed, Copyright (c) PrismarineJS
contributors. It is vendored so `gen_packet_ids.py` is reproducible offline —
packet ids and field orders are protocol facts and must never be hand-typed.

Regenerate both outputs after any upstream bump:

    python3 scripts/protocol/gen_packet_ids.py \
        --proto scripts/protocol/vendor/bedrock-1.26.45-proto.yml
