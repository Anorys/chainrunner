package com.anorys.chainrunner.core;

import com.anorys.chainrunner.config.ChainRunnerConfig;
import com.anorys.chainrunner.config.ConfigLoader;
import com.anorys.chainrunner.core.Planner.PlannedCall;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerTest {

    private List<String> plan(String order) {
        ChainRunnerConfig config = new ConfigLoader(name -> null).parse(Path.of("chainrunner.yaml"), """
                environments:
                  dev: { apis: { a: { baseUrl: http://x } } }
                variables:
                  accounts: [ACC1, ACC2]
                sequences:
                  s:
                    forEach: { account: "{{accounts}}" }
                    order: %s
                    steps:
                      - { name: "a {{account}}", path: /a }
                      - { name: "b {{account}} {{part}}", path: /b, forEach: { part: [1, 2] } }
                      - { name: "off", path: /c, enabled: false }
                """.formatted(order));
        Map<String, Object> vars = Planner.variables(config, config.environment("dev").orElseThrow(), Map.of());
        return Planner.plan(config.sequences().get(0), vars).stream().map(PlannedCall::name).toList();
    }

    @Test
    void phasesRunEachStepForAllValuesFirst() {
        assertThat(plan("phases")).containsExactly("a ACC1", "a ACC2", "b ACC1 1", "b ACC1 2", "b ACC2 1", "b ACC2 2");
    }

    @Test
    void eachRunsAllTheStepsForOneValueFirst() {
        assertThat(plan("each")).containsExactly("a ACC1", "b ACC1 1", "b ACC1 2", "a ACC2", "b ACC2 1", "b ACC2 2");
    }
}
