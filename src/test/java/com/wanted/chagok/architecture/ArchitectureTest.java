package com.wanted.chagok.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 구조 규칙 자동 검사 — 규칙의 원본은 chagok-docs/CONVENTIONS.md 1·3·8장이다.
 *
 * <p>검사할 클래스가 아직 없는 규칙은 통과(PASSED)가 아니라 건너뜀(SKIPPED)으로 남긴다.
 * 도메인 패키지가 생기면 같은 테스트가 자동으로 실제 검사를 시작한다. 이 테스트를 끄거나 규칙을 느슨하게 바꾸려면
 * 먼저 CONVENTIONS를 고치는 결정 이슈가 필요하다.
 */
class ArchitectureTest {

    private static final String ROOT = "com.wanted.chagok";

    /**
     * 우리 도메인 패키지 전체. {@code "..domain.."}만 쓰면 Spring Data의 {@code org.springframework.data.jpa.domain.support}
     * (AuditingEntityListener 등)도 "다른 도메인"으로 잡혀, 07 6.2대로 Entity에 감사 리스너를 다는 순간 규칙이 오탐한다.
     * 프로젝트 패키지에 대한 검사 강도는 그대로다.
     */
    private static final String DOMAIN_ROOT = ROOT + ".domain..";

    /** CONVENTIONS 3장: 도메인마다 부를 수 있는 다른 도메인 (자기 자신·global은 언제나 허용). */
    private static final Map<String, String[]> ALLOWED_DOMAIN_DEPENDENCIES = Map.of(
        "shop", new String[] {},
        "account", new String[] {"shop"},
        "customer", new String[] {},
        "item", new String[] {},
        "maintenance", new String[] {"customer", "item", "account"},
        "reservation", new String[] {"customer", "item", "account"},
        "cycle", new String[] {}
    );

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);
    }

    /** 실제 클래스(package-info 제외)가 하나도 없으면 이 테스트를 SKIPPED로 남긴다. */
    private static void requireClassesIn(String packagePattern) {
        Predicate<JavaClass> real = c -> !c.getSimpleName().equals("package-info");
        boolean present = classes.stream().filter(real).anyMatch(resideInAPackage(packagePattern)::test);
        Assumptions.assumeTrue(present, "검사 대상 클래스가 아직 없음 (" + packagePattern + ") → 이 규칙은 아직 검증되지 않음");
    }

    private static void requireClassesMatching(Predicate<JavaClass> predicate, String description) {
        boolean present = classes.stream().anyMatch(predicate);
        Assumptions.assumeTrue(present, "검사 대상 클래스가 아직 없음 (" + description + ") → 이 규칙은 아직 검증되지 않음");
    }

    @Test
    void global은_domain과_screen을_모른다() {
        requireClassesIn("..global..");
        noClasses().that().resideInAPackage("..global..")
            .should().dependOnClassesThat().resideInAnyPackage(DOMAIN_ROOT, "..screen..")
            .because("global은 모든 도메인이 쓰는 기반이다 (CONVENTIONS 2장)")
            .check(classes);
    }

    @Test
    void screen은_아무도_import하지_않는다() {
        requireClassesIn("..screen..");
        noClasses().that().resideOutsideOfPackage("..screen..")
            .should().dependOnClassesThat().resideInAPackage("..screen..")
            .because("screen은 화면 조립 전용이다 (CONVENTIONS 1장 4번)")
            .check(classes);
    }

    // cycle도 api(값 타입·ItemCycleCalculator)로만 부른다. cycle이 다른 것을 import하지 않는 쪽은 cycle은_순수_계산이다()가 따로 검사한다
    @ParameterizedTest(name = "{0} 도메인은 {0}.api로만 접근한다")
    @ValueSource(strings = {"shop", "account", "customer", "item", "maintenance", "reservation", "cycle"})
    void 다른_도메인은_api로만(String domain) {
        String base = "..domain." + domain;
        requireClassesIn(base + "..");
        noClasses().that().resideOutsideOfPackage(base + "..")
            .should().dependOnClassesThat(resideInAPackage(base + "..").and(not(resideInAPackage(base + ".api.."))))
            .because(domain + "의 Entity·Repository·Service·DTO는 " + domain + " 안에서만 쓴다 (CONVENTIONS 1장 1번)")
            .check(classes);
    }

    @ParameterizedTest(name = "{0} 도메인은 허용된 방향으로만 의존한다")
    @ValueSource(strings = {"shop", "account", "customer", "item", "maintenance", "reservation", "cycle"})
    void 도메인_의존_방향(String domain) {
        String base = "..domain." + domain;
        requireClassesIn(base + "..");
        String[] allowed = Arrays.stream(ALLOWED_DOMAIN_DEPENDENCIES.get(domain))
            .map(d -> "..domain." + d + "..")
            .toArray(String[]::new);
        noClasses().that().resideInAPackage(base + "..")
            .should().dependOnClassesThat(
                resideInAPackage(DOMAIN_ROOT)
                    .and(not(resideInAPackage(base + "..")))
                    .and(not(resideInAnyPackage(allowed))))
            .because("의존 방향은 CONVENTIONS 3장 표를 따른다 (외래키 방향 = 코드 방향)")
            .check(classes);
    }

    @Test
    void 도메인_사이_순환_없음() {
        long domains = classes.stream()
            .map(JavaClass::getPackageName)
            .filter(p -> p.startsWith(ROOT + ".domain."))
            .map(p -> p.substring((ROOT + ".domain.").length()).split("\\.")[0])
            .distinct().count();
        Assumptions.assumeTrue(domains >= 2, "도메인 패키지가 2개 미만 → 순환 검사는 아직 검증되지 않음");
        slices().matching(ROOT + ".domain.(*)..").should().beFreeOfCycles().check(classes);
    }

    @Test
    void cycle은_순수_계산이다() {
        requireClassesIn("..domain.cycle..");
        noClasses().that().resideInAPackage("..domain.cycle..")
            .should().dependOnClassesThat(
                resideInAPackage(DOMAIN_ROOT).and(not(resideInAPackage("..domain.cycle..")))
                    .or(resideInAnyPackage("org.springframework..", "jakarta.persistence..")))
            .because("cycle은 값을 받아 값을 돌려주는 순수 계산이다 (CONVENTIONS 3장)")
            .check(classes);
    }

    @Test
    void Controller는_Repository를_직접_쓰지_않는다() {
        requireClassesMatching(c -> c.getSimpleName().endsWith("Controller"), "*Controller");
        noClasses().that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .because("Controller는 요청·검증·응답 변환만 한다 (CONVENTIONS 4장 책임 분리)")
            .check(classes);
    }

    @Test
    void Entity에는_public_setter가_없다() {
        requireClassesMatching(c -> c.isAnnotatedWith("jakarta.persistence.Entity"), "@Entity");
        noMethods().that().areDeclaredInClassesThat().areAnnotatedWith("jakarta.persistence.Entity")
            .and().arePublic()
            .should().haveNameMatching("set[A-Z].*")
            .because("상태 변경은 의미 있는 메서드로 한다 (CONVENTIONS 4장)")
            .check(classes);
    }
}
