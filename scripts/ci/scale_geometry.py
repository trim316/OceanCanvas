"""Exact center-zero operation geometry within the configured 20k canvas."""
from dataclasses import dataclass


@dataclass(frozen=True)
class ScaleGeometry:
    requested_width: int
    natural_border: bool = False
    canvas_width: int = 20000

    @property
    def operation_width(self):
        return self.requested_width + (32 if self.natural_border else 0)

    @property
    def authored_width(self):
        return min(self.operation_width, self.canvas_width)

    @property
    def chunks(self):
        radius = self.authored_width // 2
        return ((radius - 1) // 16 - (-radius // 16) + 1) ** 2

    @property
    def command(self):
        return f'oceancanvas pregen start {self.requested_width // 2} 0 0 confirm' + (' border' if self.natural_border else '')

    def checkpoint_fields(self):
        # Preserve the existing exact identity of default no-border checkpoints.
        if not self.natural_border:
            return {}
        return dict(requestedBlocks=self.requested_width, naturalBorderBlocks=16,
                    operationBlocks=self.operation_width,
                    minBlockX=-self.authored_width // 2, maxBlockX=self.authored_width // 2 - 1,
                    minBlockZ=-self.authored_width // 2, maxBlockZ=self.authored_width // 2 - 1)
