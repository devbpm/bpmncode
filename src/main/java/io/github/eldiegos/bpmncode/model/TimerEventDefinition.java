package io.github.eldiegos.bpmncode.model;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Definición de evento de temporizador BPMN ({@code bpmn:timerEventDefinition}) derivada del {@code @Scheduled} de
 * Spring del método que inicia el proceso.
 * <p>
 * Responsabilidad: describir cuándo vence el temporizador con los mismos datos que usa el planificador de Spring, para
 * que el diagrama y la ejecución no puedan divergir.
 * <p>
 * Invariantes: una expresión {@link Schedule#CRON} es una expresión cron de Spring de seis campos; en
 * {@link Schedule#FIXED_RATE} y {@link Schedule#FIXED_DELAY} el intervalo es positivo; los valores ya tienen resueltos
 * sus {@code ${…}}.
 *
 * @param schedule tipo de programación de Spring
 * @param cron expresión cron, si la programación es {@link Schedule#CRON}
 * @param interval intervalo, si la programación es periódica
 * @param initialDelay espera antes de la primera ejecución, si la hay
 * @param zone zona horaria de la expresión cron, si se declaró
 * @see FlowNode.StartEvent#eventDefinition()
 */
public record TimerEventDefinition(Schedule schedule, Optional<String> cron, Optional<Duration> interval,
		Optional<Duration> initialDelay, Optional<String> zone) implements EventDefinition {

	/**
	 * Tipo de programación de un {@code @Scheduled}.
	 * <p>
	 * Responsabilidad: distinguir las tres formas de programar de Spring, que BPMN representa con {@code timeCycle}.
	 * <p>
	 * Invariantes: cada constante corresponde a un atributo de {@code @Scheduled}.
	 *
	 * @see TimerEventDefinition
	 */
	public enum Schedule {

		/** {@code @Scheduled(cron = …)}: según una expresión cron. */
		CRON,

		/** {@code @Scheduled(fixedRate = …)}: cada intervalo, contado desde el inicio de la ejecución anterior. */
		FIXED_RATE,

		/** {@code @Scheduled(fixedDelay = …)}: cada intervalo, contado desde el final de la ejecución anterior. */
		FIXED_DELAY
	}

	public TimerEventDefinition {
		Objects.requireNonNull(schedule, "schedule");
		Objects.requireNonNull(cron, "cron");
		Objects.requireNonNull(interval, "interval");
		Objects.requireNonNull(initialDelay, "initialDelay");
		Objects.requireNonNull(zone, "zone");
		var cronExpected = schedule == Schedule.CRON;
		if (cron.isPresent() != cronExpected || interval.isPresent() == cronExpected) {
			throw new IllegalArgumentException("A " + schedule + " timer needs " + (cronExpected ? "a cron expression"
					: "an interval") + " and nothing else");
		}
		if (interval.filter(value -> value.isNegative() || value.isZero()).isPresent()) {
			throw new IllegalArgumentException("A timer interval must be positive");
		}
	}

	/**
	 * Temporizador según una expresión cron.
	 *
	 * @param cron expresión cron de Spring
	 * @param zone zona horaria, si se declaró
	 * @return la definición
	 */
	public static TimerEventDefinition cron(String cron, Optional<String> zone) {
		return new TimerEventDefinition(Schedule.CRON, Optional.of(cron), Optional.empty(), Optional.empty(), zone);
	}

	/**
	 * Temporizador periódico.
	 *
	 * @param schedule {@link Schedule#FIXED_RATE} o {@link Schedule#FIXED_DELAY}
	 * @param interval intervalo
	 * @param initialDelay espera inicial, si la hay
	 * @return la definición
	 */
	public static TimerEventDefinition periodic(Schedule schedule, Duration interval, Optional<Duration> initialDelay) {
		return new TimerEventDefinition(schedule, Optional.empty(), Optional.of(interval), initialDelay, Optional.empty());
	}

	/**
	 * Valor de {@code bpmn:timeCycle}: la expresión cron, o el intervalo repetido de ISO 8601 ({@code R/PT5M}).
	 *
	 * @return el ciclo
	 */
	public String timeCycle() {
		return cron.orElseGet(() -> "R/" + interval.orElseThrow());
	}
}
