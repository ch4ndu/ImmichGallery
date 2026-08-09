---
name: trip-init
description: Initialize and adapt the project-local TRIP workflow suite
---

# TRIP Initialization

Read [the complete initialization workflow](references/workflow.md) before taking any action. It preserves the required discovery, classification, documentation generation, mandatory user review, adaptation, validation, and next-step gates.

Do not run another public TRIP workflow until `.agents/trip/initialized.json` exists. After the user has approved the project-specific architecture document, invoke `.agents/trip/bin/initialize_trip.py` with explicit commands and an adaptation JSON mapping generated from the repository exploration. The initializer fails closed if any active placeholder or adaptation marker remains.
