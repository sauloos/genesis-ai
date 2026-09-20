package ai.genesisbrands.agent.brandbook;

import ai.genesisbrands.agent.core.DirectionBrief;
import ai.genesisbrands.agent.copy.CopyOutput;
import ai.genesisbrands.agent.logo.LogoOutput;
import ai.genesisbrands.agent.playbook.PlaybookOutput;
import ai.genesisbrands.agent.visualidentity.VisualIdentityOutput;

public record BrandBookInput(
    DirectionBrief brief,
    PlaybookOutput playbook,
    CopyOutput copy,
    VisualIdentityOutput visualIdentity,
    LogoOutput logo
) {}
