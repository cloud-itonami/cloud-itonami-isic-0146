# Business Model: Poultry-Farm Operations Coordinator

## Classification

- Repository: `cloud-itonami-isic-0146`
- ISIC Rev. 4: `0146`
- Industry: Raising of poultry
- Social impact: animal-welfare, food-security, rural-employment

## Customer

- Small-to-medium poultry farms (broiler grow-out, layer/egg-production operations)
- Breeding-stock and hatchery-adjacent operations
- Contract/independent poultry producers
- Cooperative and integrator-affiliated poultry operations

## Offer

- Flock management and record-keeping, including mortality and egg-production data
- Veterinary appointment coordination
- Health and biosecurity tracking (e.g. HPAI risk surfacing)
- Supply procurement coordination
- Audit trail and transparency

## Revenue

- SaaS subscription (per-bird-per-month pricing)
- Supply chain integration fees
- API access for veterinary partners
- Data analytics and reporting add-ons

## Trust Controls

- No culling or depopulation decisions without human sign-off
- No direct treatment administration
- All veterinary recommendations are proposals, not commands
- Facility (barn/house) registration is required before any operation
- All animal health/biosecurity concerns are automatically escalated
- High-cost supply orders require approval
- Audit ledger is append-only and never editable

## What we do NOT do

- **Veterinary treatment decisions** — the veterinarian decides treatment
- **Animal welfare decisions** — the farm operator decides welfare actions
- **Economic decisions** (culling, depopulation, breeding) — remain human authority
- **Direct animal handling** — the robot manages records and logistics only
- **Outbreak declarations / animal-health authority contact** — flagged
  concerns (e.g. suspected HPAI) are surfaced for human/veterinary judgment
  only

## Supported Operations

### Flock Record Logging
- Daily flock counts
- Weight tracking
- Health status notes
- Mortality tracking
- Egg-production data (layer operations — logging only, not decision-making)

### Veterinary Coordination
- Schedule vet visits
- Track vet exam results
- Propose follow-up care (not order it directly)

### Health/Biosecurity Concern Escalation
- Flag suspected disease (e.g. Highly Pathogenic Avian Influenza / HPAI,
  Newcastle Disease, Infectious Bronchitis, Infectious Bursal Disease)
- Report injuries or welfare concerns
- Automatic escalation to farm operator/veterinarian

### Supply Procurement
- Feed orders
- Veterinary supply orders
- Biosecurity-equipment procurement (PPE, disinfectant, perimeter controls)
- Cost threshold escalation for large orders
