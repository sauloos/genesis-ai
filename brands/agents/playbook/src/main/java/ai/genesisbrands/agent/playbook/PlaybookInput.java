package ai.genesisbrands.agent.playbook;

import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.copy.CopyOutput;
import ai.genesisbrands.agent.logo.LogoOutput;
import ai.genesisbrands.agent.visualidentity.VisualIdentityOutput;

public record PlaybookInput(
    DirectionBrief brief,
    CopyOutput copy,
    VisualIdentityOutput visualIdentity,
    LogoOutput logo
) {}
