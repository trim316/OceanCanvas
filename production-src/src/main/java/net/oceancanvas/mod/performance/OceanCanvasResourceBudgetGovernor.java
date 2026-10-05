package net.oceancanvas.mod.performance;

/**
 * Explicit resource-budget governor for long-running Pregen admission.
 *
 * <p>This model is intentionally dependency-free and one-way: it may only hold or
 * reduce the rate requested by the existing adaptive controller. It never creates
 * work, retires work, owns tickets, or raises the learned rate. That keeps the
 * proven v230.5 target-ordering/admission machinery authoritative while making the
 * actual machine budgets visible and executable.</p>
 */
public class OceanCanvasResourceBudgetGovernor {
	protected OceanCanvasResourceBudgetGovernor() {}

	public enum State { CLEAR, ELEVATED, SATURATED }
	public enum Limiter { NONE, CPU, HEAP, IO, TICKETS, MULTIPLE }

	public record Decision(
			State state,
			Limiter limiter,
			int requestedRate,
			int admissionCap,
			double cpuWorkMs,
			double cpuSoftBudgetMs,
			double cpuHardBudgetMs,
			double heapUseFraction,
			double heapSoftLimit,
			double heapHardLimit,
			int transientTickets,
			int ticketSoftLimit,
			int ticketHardLimit,
			String reason) {
		public boolean constrained() { return admissionCap < requestedRate; }
		public int ticketHeadroom() { return Math.max(0, ticketHardLimit - transientTickets); }
		public double heapHeadroom() { return Math.max(0.0D, heapHardLimit - heapUseFraction); }
		public double cpuHeadroomMs() { return Math.max(0.0D, cpuHardBudgetMs - cpuWorkMs); }
	}

	public static Decision idle() {
		return new Decision(State.CLEAR, Limiter.NONE, 0, 0, 0.0D, 45.0D, 55.0D,
				0.0D, 0.76D, 0.82D, 0, 1, 1, "No active Pregen resource budget");
	}

	/**
	 * Evaluate explicit machine budgets. Thresholds deliberately mirror the
	 * controller's already-proven pressure boundaries where possible.
	 */
	public static Decision evaluate(
			int requestedRate,
			double cpuWorkMs,
			double heapUseFraction,
			OceanCanvasIoPressureGovernor.Decision io,
			int transientTickets,
			int ticketSoftLimit,
			int ticketHardLimit) {
		int requested = Math.max(0, requestedRate);
		double work = Math.max(0.0D, cpuWorkMs);
		double heap = Math.max(0.0D, Math.min(1.0D, heapUseFraction));
		int tickets = Math.max(0, transientTickets);
		int softTickets = Math.max(1, ticketSoftLimit);
		int hardTickets = Math.max(softTickets + 1, ticketHardLimit);

		final double cpuSoftMs = 45.0D;
		final double cpuHardMs = 55.0D;
		final double heapSoft = 0.76D;
		final double heapHard = 0.82D;

		int cpuCap = requested;
		if (work >= cpuHardMs) cpuCap = 0;
		else if (work >= cpuSoftMs && requested > 0) cpuCap = Math.max(1, (int)Math.ceil(requested * 0.50D));

		int heapCap = requested;
		if (heap >= heapHard) heapCap = 0;
		else if (heap >= heapSoft && requested > 0) heapCap = Math.max(1, (int)Math.ceil(requested * 0.50D));

		int ioCap = io == null ? requested : Math.max(0, Math.min(requested, io.admissionCap()));

		int ticketCap = requested;
		if (tickets >= hardTickets) ticketCap = 0;
		else if (tickets >= softTickets && requested > 0) ticketCap = Math.max(1, (int)Math.ceil(requested * 0.50D));

		int cap = Math.min(Math.min(cpuCap, heapCap), Math.min(ioCap, ticketCap));
		boolean cpuLimited = cpuCap == cap && cpuCap < requested;
		boolean heapLimited = heapCap == cap && heapCap < requested;
		boolean ioLimited = ioCap == cap && ioCap < requested;
		boolean ticketLimited = ticketCap == cap && ticketCap < requested;
		int limiterCount = (cpuLimited ? 1 : 0) + (heapLimited ? 1 : 0) + (ioLimited ? 1 : 0) + (ticketLimited ? 1 : 0);
		Limiter limiter = limiterCount == 0 ? Limiter.NONE
				: limiterCount > 1 ? Limiter.MULTIPLE
				: cpuLimited ? Limiter.CPU
				: heapLimited ? Limiter.HEAP
				: ioLimited ? Limiter.IO : Limiter.TICKETS;
		State state = cap == 0 && requested > 0 ? State.SATURATED
				: cap < requested ? State.ELEVATED : State.CLEAR;

		String reason = switch (limiter) {
			case CPU -> "CPU tick-work budget";
			case HEAP -> "Heap budget";
			case IO -> io == null ? "I/O budget" : io.reason();
			case TICKETS -> "Transient ticket budget";
			case MULTIPLE -> "Multiple resource budgets";
			default -> "Resource budgets clear";
		};
		return new Decision(state, limiter, requested, cap, work, cpuSoftMs, cpuHardMs,
				heap, heapSoft, heapHard, tickets, softTickets, hardTickets, reason);
	}
}
