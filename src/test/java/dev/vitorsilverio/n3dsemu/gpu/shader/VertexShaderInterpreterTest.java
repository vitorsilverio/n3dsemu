package dev.vitorsilverio.n3dsemu.gpu.shader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// RFC-N3DSEMU G5/PR2: roda o interpretador contra o `.shbin` REAL do `simple_tri`
/// (`vshader.v.pica`) com entradas conhecidas e confere a saída contra o valor calculado à mão —
/// exatamente a técnica de teste que a task pede ("determinístico, não precisa de GPU").
class VertexShaderInterpreterTest {
    private static final Path SIMPLE_TRI_SHBIN = Path.of("testdata/shaders/simple_tri.shbin");

    private static ShaderBinary load() throws IOException {
        return ShaderBinary.parse(Files.readAllBytes(SIMPLE_TRI_SHBIN));
    }

    /// `mov r0.xyz,inpos / mov r0.w,ones / outpos = projection*r0 / outclr = inclr` com
    /// `projection` = identidade: `outpos` deve sair igual a `(inpos.xyz, 1.0)` e `outclr` igual
    /// a `inclr`, sem nenhuma transformação real — o teste mais simples que exercita a cadeia
    /// inteira (leitura de input, MOV com swizzle/máscara, DP4 contra constante de runtime, MOV
    /// de saída, END).
    @Test
    void simpleTriWithIdentityProjectionPassesThroughPositionAndColor() throws IOException {
        ShaderBinary shader = load();
        ShaderBinary.Executable exec = shader.executables().get(0);

        float[][] input = new float[VertexShaderInterpreter.NUM_INPUT_REGISTERS][4];
        input[0] = new float[]{1f, 2f, 3f, 999f}; // inpos (v0) — w é ignorado pelo shader (forçado a 1.0)
        input[1] = new float[]{0.25f, 0.5f, 0.75f, 1.0f}; // inclr (v1)

        float[][] floatConstants = VertexShaderInterpreter.embeddedFloatConstants(exec);
        setIdentityProjection(floatConstants);

        float[][] output = VertexShaderInterpreter.run(shader, exec.mainOffset(), input, floatConstants,
                VertexShaderInterpreter.embeddedIntConstants(exec), VertexShaderInterpreter.embeddedBoolConstants(exec));

        ShaderBinary.OutputRegister position = exec.outputRegisters().get(0);
        ShaderBinary.OutputRegister color = exec.outputRegisters().get(1);
        float[] outpos = output[position.registerId()];
        float[] outclr = output[color.registerId()];

        assertEquals(1f, outpos[0], 1e-6f);
        assertEquals(2f, outpos[1], 1e-6f);
        assertEquals(3f, outpos[2], 1e-6f);
        assertEquals(1f, outpos[3], 1e-6f); // "ones" (myconst.y) via mov r0.w, ones

        assertEquals(0.25f, outclr[0], 1e-6f);
        assertEquals(0.5f, outclr[1], 1e-6f);
        assertEquals(0.75f, outclr[2], 1e-6f);
        assertEquals(1.0f, outclr[3], 1e-6f);
    }

    @Test
    void projectionMatrixIsActuallyAppliedNotJustPassthrough() throws IOException {
        ShaderBinary shader = load();
        ShaderBinary.Executable exec = shader.executables().get(0);

        float[][] input = new float[VertexShaderInterpreter.NUM_INPUT_REGISTERS][4];
        input[0] = new float[]{1f, 0f, 0f, 0f};
        input[1] = new float[]{1f, 1f, 1f, 1f};

        float[][] floatConstants = VertexShaderInterpreter.embeddedFloatConstants(exec);
        // Escala uniforme por 2 (matriz diagonal 2*I) — outpos deve sair (2,0,0,2) para
        // inpos=(1,0,0) (w forçado a 1 pelo shader, escalado por 2 na linha w).
        floatConstants[0] = new float[]{2f, 0f, 0f, 0f};
        floatConstants[1] = new float[]{0f, 2f, 0f, 0f};
        floatConstants[2] = new float[]{0f, 0f, 2f, 0f};
        floatConstants[3] = new float[]{0f, 0f, 0f, 2f};

        float[][] output = VertexShaderInterpreter.run(shader, exec.mainOffset(), input, floatConstants,
                VertexShaderInterpreter.embeddedIntConstants(exec), VertexShaderInterpreter.embeddedBoolConstants(exec));

        float[] outpos = output[exec.outputRegisters().get(0).registerId()];
        assertEquals(2f, outpos[0], 1e-6f);
        assertEquals(0f, outpos[1], 1e-6f);
        assertEquals(0f, outpos[2], 1e-6f);
        assertEquals(2f, outpos[3], 1e-6f);
    }

    private static void setIdentityProjection(float[][] floatConstants) {
        floatConstants[0] = new float[]{1f, 0f, 0f, 0f};
        floatConstants[1] = new float[]{0f, 1f, 0f, 0f};
        floatConstants[2] = new float[]{0f, 0f, 1f, 0f};
        floatConstants[3] = new float[]{0f, 0f, 0f, 1f};
    }

    // --- G6.3: CMP (formato 1c) e MAD/MADI (formato 5/5i) -----------------------------------
    //
    // Não há `.shbin` real disponível que use CMP/MAD (nenhum exemplo do corpus os exercita
    // ainda) — os testes abaixo montam `ShaderBinary`s sintéticos, codificando as instruções
    // bit a bit conforme o layout real do nihstro (`include/nihstro/shader_bytecode.h`, ver
    // Javadoc de `VertexShaderInterpreter`), não um `.shbin` compilado.

    private static final int OPCODE_IDENTIFY_SHIFT = 27; // bits 27-31: 5 bits fixos que identificam CMP
    private static final int MAD_IDENTIFY_SHIFT = 29; // bits 29-31: 3 bits fixos que identificam MAD/MADI
    private static final int END_WORD = 0x22 << 26;

    /// Descritor de operando neutro: máscara de destino = todos os componentes, swizzle
    /// identidade (x,y,z,w passthrough) e sem negação em nenhum dos 3 slots (src1/src2/src3) —
    /// mesmo valor sirva tanto para o formato 1/1c (só usa os slots src1/src2) quanto para MAD
    /// (usa os 3).
    private static int identityDescriptor() {
        int identitySwizzle = 0b00_01_10_11; // component i lê do component i da fonte
        int destMaskAll = 0xF;
        return destMaskAll | (identitySwizzle << 5) | (identitySwizzle << 14) | (identitySwizzle << 23);
    }

    private static ShaderBinary syntheticShader(int... programCode) {
        return new ShaderBinary(programCode, new int[]{identityDescriptor()}, List.of());
    }

    /// `CMP` (opcode `0x2E`/`0x2F`, formato 1c): compara componente x com `cmp.x`-op e componente
    /// y com `cmp.y`-op, grava o resultado nas condition codes — não num registrador normal. Este
    /// teste usa `LessThan` em x (falso: `5 < 2` é falso) e `GreaterEqual` em y (verdadeiro:
    /// `5 >= 3`), provando que os dois operadores de 3 bits são lidos e aplicados
    /// independentemente por componente.
    @Test
    void cmpWritesIndependentConditionCodesPerComponent() {
        int src1Index = 0; // v0
        int src2Index = 1; // v1
        int opX = 2; // LessThan
        int opY = 5; // GreaterEqual
        int cmpWord = 0 /* operand_desc_id */
                | (src2Index << 7)
                | (src1Index << 12)
                | (opY << 21)
                | (opX << 24)
                | (0b10111 << OPCODE_IDENTIFY_SHIFT);
        ShaderBinary shader = syntheticShader(cmpWord, END_WORD);

        float[][] input = new float[VertexShaderInterpreter.NUM_INPUT_REGISTERS][4];
        input[0] = new float[]{5f, 5f, 0f, 0f}; // v0 = src1
        input[1] = new float[]{2f, 3f, 0f, 0f}; // v1 = src2

        VertexShaderInterpreter.Result result = VertexShaderInterpreter.runDetailed(shader, 0, input,
                new float[VertexShaderInterpreter.NUM_FLOAT_CONSTANTS][4],
                new int[VertexShaderInterpreter.NUM_INT_CONSTANTS][4],
                new boolean[VertexShaderInterpreter.NUM_BOOL_CONSTANTS]);

        assertFalse(result.conditionCode()[0], "cmp.x: 5 < 2 deveria ser falso");
        assertTrue(result.conditionCode()[1], "cmp.y: 5 >= 3 deveria ser verdadeiro");
    }

    /// `MAD` (opcode `0x38`-`0x3F`, não invertido): `src2` é o operando "largo" (7 bits, bits
    /// 10-16), `src3` o "estreito" (5 bits, bits 5-9) — resultado = `src1*src2+src3`,
    /// componente a componente.
    @Test
    void madRegisterFormComputesFusedMultiplyAdd() {
        int src1Index = 0; // v0
        int src2Index = 1; // v1 (campo largo desta forma)
        int src3Index = 2; // v2 (campo estreito desta forma)
        int destIndex = 0; // o0
        int madWord = 0 /* operand_desc_id */
                | (src3Index << 5)
                | (src2Index << 10)
                | (src1Index << 17)
                | (destIndex << 24)
                | (0b111 << MAD_IDENTIFY_SHIFT);
        ShaderBinary shader = syntheticShader(madWord, END_WORD);

        float[][] input = new float[VertexShaderInterpreter.NUM_INPUT_REGISTERS][4];
        input[0] = new float[]{2f, 3f, 4f, 5f};
        input[1] = new float[]{10f, 10f, 10f, 10f};
        input[2] = new float[]{1f, 1f, 1f, 1f};

        float[][] output = VertexShaderInterpreter.run(shader, 0, input,
                new float[VertexShaderInterpreter.NUM_FLOAT_CONSTANTS][4],
                new int[VertexShaderInterpreter.NUM_INT_CONSTANTS][4],
                new boolean[VertexShaderInterpreter.NUM_BOOL_CONSTANTS]);

        assertEquals(21f, output[0][0], 1e-6f);
        assertEquals(31f, output[0][1], 1e-6f);
        assertEquals(41f, output[0][2], 1e-6f);
        assertEquals(51f, output[0][3], 1e-6f);
    }

    /// `MADI` (opcode `0x30`-`0x37`, invertido): `src3` vira o operando "largo" (7 bits, bits
    /// 5-11) — usado aqui para referenciar uma constante float (`c0`, índice 32), provando que o
    /// campo largo realmente alcança o espaço de registrador de constantes só quando invertido
    /// (o campo "estreito", 5 bits, não alcançaria índice 32).
    @Test
    void madiInvertedFormRoutesWideFieldToSrc3() {
        int src1Index = 0; // v0
        int src2Index = 1; // v1 (campo estreito nesta forma)
        int src3Index = 32; // c0 (campo largo nesta forma — só alcançável com 7 bits)
        int destIndex = 1; // o1
        int madiWord = 0 /* operand_desc_id */
                | (src3Index << 5)
                | (src2Index << 12)
                | (src1Index << 17)
                | (destIndex << 24)
                | (0b110 << MAD_IDENTIFY_SHIFT);
        ShaderBinary shader = syntheticShader(madiWord, END_WORD);

        float[][] input = new float[VertexShaderInterpreter.NUM_INPUT_REGISTERS][4];
        input[0] = new float[]{2f, 3f, 4f, 5f};
        input[1] = new float[]{10f, 10f, 10f, 10f};
        float[][] floatConstants = new float[VertexShaderInterpreter.NUM_FLOAT_CONSTANTS][4];
        floatConstants[0] = new float[]{100f, 100f, 100f, 100f};

        float[][] output = VertexShaderInterpreter.run(shader, 0, input, floatConstants,
                new int[VertexShaderInterpreter.NUM_INT_CONSTANTS][4],
                new boolean[VertexShaderInterpreter.NUM_BOOL_CONSTANTS]);

        assertEquals(120f, output[1][0], 1e-6f);
        assertEquals(130f, output[1][1], 1e-6f);
        assertEquals(140f, output[1][2], 1e-6f);
        assertEquals(150f, output[1][3], 1e-6f);
    }

    // --- G6.4: controle de fluxo (JMPC/JMPU/IFC/IFU/CALL*/LOOP/BREAK*) ----------------------
    //
    // Layout de bits + semântica de pilha transcritos do nihstro (`shader_bytecode.h`) e do
    // interpretador de referência real do Citra (`shader_interpreter.cpp`, fork lime3ds, `curl`
    // direto — ver Javadoc de `VertexShaderInterpreter` e a task `g6.4-...md`). Sem `.shbin` real
    // disponível que use laços/condicionais — programas sintéticos, words calculados via shift.

    private static int movWord(int srcIndex, int destIndex) {
        // MOV (0x13, format1u): desc=0 (identidade), src1 em bits(12,7), dest em bits(21,5).
        return (0x13 << 26) | (destIndex << 21) | (srcIndex << 12);
    }

    private static int endWord() {
        return 0x22 << 26;
    }

    /// `JMPC` (`0x2C`): `dest_offset` em bits(10,12), `op`/`refx`/`refy` em bits(22,2)/25/24 —
    /// mesmo layout de `FlowControlType`. `op=JustX(2)`, `refx=1`: salta se `cmp.x==true`.
    private static int jmpcWord(int destOffset, int op, boolean refx, boolean refy) {
        return (0x2C << 26) | (destOffset << 10) | (op << 22) | ((refy ? 1 : 0) << 24) | ((refx ? 1 : 0) << 25);
    }

    private static int cmpWord(int src1Index, int src2Index, int opX, int opY) {
        return (src2Index << 7) | (src1Index << 12) | (opY << 21) | (opX << 24) | (0b10111 << 27);
    }

    @Test
    void jmpcTakesBranchWhenConditionMatchesAndSkipsInstructionsInBetween() {
        // v0->o0 (não deveria rodar, pulado pelo salto); v1->o1 (destino do salto); END.
        ShaderBinary shader = syntheticShader(
                cmpWord(0, 1, 2 /*LessThan*/, 2 /*LessThan*/), // pc0: cmp v0,v1 (x,y ambos LessThan)
                jmpcWord(3, 2 /*JustX*/, true, false),          // pc1: jmpc pc3 se cmp.x==true
                movWord(0, 0),                                  // pc2: mov o0,v0 (deveria ser pulado)
                movWord(1, 1),                                  // pc3: mov o1,v1 (destino do salto)
                endWord());                                     // pc4

        float[][] input = new float[VertexShaderInterpreter.NUM_INPUT_REGISTERS][4];
        input[0] = new float[]{1f, 1f, 1f, 1f}; // v0 < v1 -> cmp.x = true
        input[1] = new float[]{9f, 9f, 9f, 9f};

        float[][] output = VertexShaderInterpreter.run(shader, 0, input,
                new float[VertexShaderInterpreter.NUM_FLOAT_CONSTANTS][4],
                new int[VertexShaderInterpreter.NUM_INT_CONSTANTS][4],
                new boolean[VertexShaderInterpreter.NUM_BOOL_CONSTANTS]);

        assertEquals(0f, output[0][0], 1e-6f, "o0 não deveria ter sido escrito (instrução pulada)");
        assertEquals(9f, output[1][0], 1e-6f, "o1 deveria ter recebido v1 no destino do salto");
    }

    @Test
    void jmpcDoesNotBranchWhenConditionDoesNotMatch() {
        ShaderBinary shader = syntheticShader(
                cmpWord(0, 1, 4 /*GreaterThan*/, 4 /*GreaterThan*/), // v0 > v1? falso
                jmpcWord(3, 2 /*JustX*/, true, false),
                movWord(0, 0), // deve rodar (não pulado)
                movWord(1, 1),
                endWord());

        float[][] input = new float[VertexShaderInterpreter.NUM_INPUT_REGISTERS][4];
        input[0] = new float[]{1f, 1f, 1f, 1f};
        input[1] = new float[]{9f, 9f, 9f, 9f};

        float[][] output = VertexShaderInterpreter.run(shader, 0, input,
                new float[VertexShaderInterpreter.NUM_FLOAT_CONSTANTS][4],
                new int[VertexShaderInterpreter.NUM_INT_CONSTANTS][4],
                new boolean[VertexShaderInterpreter.NUM_BOOL_CONSTANTS]);

        assertEquals(1f, output[0][0], 1e-6f, "o0 deveria ter sido escrito (salto não tomado)");
        assertEquals(9f, output[1][0], 1e-6f);
    }

    /// `IFC` (`0x28`): `num_instructions` em bits(0,8) = tamanho do bloco "então". Sem "senão"
    /// aqui (`dest_offset` == `endAddress` do bloco "então", ou seja, cai direto no fim).
    private static int ifcWord(int destOffset, int numInstructions, int op, boolean refx, boolean refy) {
        return (0x28 << 26) | (numInstructions) | (destOffset << 10) | (op << 22)
                | ((refy ? 1 : 0) << 24) | ((refx ? 1 : 0) << 25);
    }

    @Test
    void ifcRunsThenBlockWhenConditionTrueAndSkipsItWhenFalse() {
        // pc0: cmp; pc1: ifc (então: pc2, 1 instrução, dest_offset=3=fim); pc2: mov o0,v1; pc3: end.
        ShaderBinary shader = syntheticShader(
                cmpWord(0, 1, 2 /*LessThan*/, 2),
                ifcWord(3, 1, 2 /*JustX*/, true, false),
                movWord(1, 0), // então: o0 = v1
                endWord());

        float[][] input = new float[VertexShaderInterpreter.NUM_INPUT_REGISTERS][4];
        input[0] = new float[]{1f, 1f, 1f, 1f};
        input[1] = new float[]{9f, 9f, 9f, 9f};

        float[][] trueOutput = VertexShaderInterpreter.run(shader, 0, input,
                new float[VertexShaderInterpreter.NUM_FLOAT_CONSTANTS][4],
                new int[VertexShaderInterpreter.NUM_INT_CONSTANTS][4],
                new boolean[VertexShaderInterpreter.NUM_BOOL_CONSTANTS]);
        assertEquals(9f, trueOutput[0][0], 1e-6f, "condição verdadeira: bloco então deveria ter rodado");

        input[0] = new float[]{9f, 9f, 9f, 9f};
        input[1] = new float[]{1f, 1f, 1f, 1f}; // agora v0 < v1 é falso
        float[][] falseOutput = VertexShaderInterpreter.run(shader, 0, input,
                new float[VertexShaderInterpreter.NUM_FLOAT_CONSTANTS][4],
                new int[VertexShaderInterpreter.NUM_INT_CONSTANTS][4],
                new boolean[VertexShaderInterpreter.NUM_BOOL_CONSTANTS]);
        assertEquals(0f, falseOutput[0][0], 1e-6f, "condição falsa: bloco então deveria ter sido pulado");
    }

    /// `CALL` (`0x24`): `dest_offset` em bits(10,12) = alvo; `num_instructions` em bits(0,8) =
    /// tamanho da sub-rotina (retorno = `dest_offset + num_instructions`).
    private static int callWord(int destOffset, int numInstructions) {
        return (0x24 << 26) | numInstructions | (destOffset << 10);
    }

    @Test
    void callJumpsToSubroutineAndReturnsAfterIt() {
        // pc0: call pc3 (sub-rotina de 1 instrução, pc3); pc1: mov o1,v1 (depois do retorno);
        // pc2: end; pc3: mov o0,v0 (corpo da sub-rotina, único, retorno cai em pc0+1=pc1... não,
        // retorno = dest_offset(3)+num_instructions(1)=4 -> cai em pc1? Não: end_address=4 é
        // comparado contra fallthrough (pc3+1=4) -> fecha o escopo e usa returnAddress=pc0+1=pc1.
        ShaderBinary shader = syntheticShader(
                callWord(3, 1), // pc0
                movWord(1, 1),  // pc1: depois do retorno
                endWord(),      // pc2
                movWord(0, 0)); // pc3: corpo da sub-rotina

        float[][] input = new float[VertexShaderInterpreter.NUM_INPUT_REGISTERS][4];
        input[0] = new float[]{5f, 5f, 5f, 5f};
        input[1] = new float[]{7f, 7f, 7f, 7f};

        float[][] output = VertexShaderInterpreter.run(shader, 0, input,
                new float[VertexShaderInterpreter.NUM_FLOAT_CONSTANTS][4],
                new int[VertexShaderInterpreter.NUM_INT_CONSTANTS][4],
                new boolean[VertexShaderInterpreter.NUM_BOOL_CONSTANTS]);

        assertEquals(5f, output[0][0], 1e-6f, "o0 deveria ter sido escrito dentro da sub-rotina");
        assertEquals(7f, output[1][0], 1e-6f, "o1 deveria ter sido escrito depois do retorno da CALL");
    }

    /// `LOOP` (`0x29`): `int_uniform_id` em bits(22,2) indexa uma constante inteira
    /// (`x`=contador,`y`=aL inicial,`z`=incremento). `dest_offset`+1 = endereço de saída do laço.
    private static int loopWord(int destOffset, int intUniformId) {
        return (0x29 << 26) | (destOffset << 10) | (intUniformId << 22);
    }

    private static final int TEMP_R0 = 16; // r0 — único registrador legível E gravável (o0-o15 são write-only)

    /// `ADD` (formato 1, `0x00`): monta o word bit a bit (descritor `0`, identidade).
    private static int bitsAdd(int src1Index, int src2Index, int destIndex) {
        return (src2Index << 7) | (src1Index << 12) | (destIndex << 21);
    }

    @Test
    void loopRepeatsBodyAccordingToIntUniformCounter() {
        // Acumula v0 em r0 (temp — registrador de saída não é legível como fonte, mesmo padrão do
        // PICA200 real) a cada iteração, depois copia r0->o0 fora do laço.
        // int0 = {count=2 (contador é "N-1", roda 3x), initialAl=0, increment=1, unused=0}.
        int addWord = bitsAdd(TEMP_R0, 0, TEMP_R0); // pc1: r0 += v0
        ShaderBinary shader = syntheticShader(
                loopWord(1, 0),          // pc0: loop dest_offset=1 -> endAddress=2 (=pc2), entry=pc1
                addWord,                 // pc1: corpo do laço (única instrução)
                movWord(TEMP_R0, 0),     // pc2: fora do laço — o0 = r0
                endWord());              // pc3

        float[][] input = new float[VertexShaderInterpreter.NUM_INPUT_REGISTERS][4];
        input[0] = new float[]{1f, 1f, 1f, 1f};
        int[][] intConstants = new int[VertexShaderInterpreter.NUM_INT_CONSTANTS][4];
        intConstants[0] = new int[]{2, 0, 1, 0}; // downcounter=2 -> corpo roda 3x

        float[][] output = VertexShaderInterpreter.run(shader, 0, input,
                new float[VertexShaderInterpreter.NUM_FLOAT_CONSTANTS][4], intConstants,
                new boolean[VertexShaderInterpreter.NUM_BOOL_CONSTANTS]);

        assertEquals(3f, output[0][0], 1e-6f, "o0 deveria ter acumulado 3 iterações (contador=2 -> 3x)");
    }

    /// `BREAKC` (`0x23`): sai do laço mais interno assim que a condição é verdadeira, antes do
    /// contador natural terminar.
    private static int breakcWord(int op, boolean refx, boolean refy) {
        return (0x23 << 26) | (op << 22) | ((refy ? 1 : 0) << 24) | ((refx ? 1 : 0) << 25);
    }

    @Test
    void breakcExitsLoopEarlyBeforeNaturalCounterEnds() {
        // Corpo: acumula r0+=v0, depois cmp (sempre verdadeiro) + breakc — a 1ª iteração acumula
        // e então quebra, então mesmo com contador programado para 5 iterações só 1 deveria rodar.
        int addWord = bitsAdd(TEMP_R0, 0, TEMP_R0);
        ShaderBinary shader = syntheticShader(
                loopWord(3, 0),                       // pc0: loop dest_offset=3 -> endAddress=4(=pc4), entry=pc1
                addWord,                                // pc1: corpo — r0 += v0
                cmpWord(0, 1, 2 /*LessThan*/, 2),      // pc2: cmp v0,v1 (sempre true: 1<9)
                breakcWord(2 /*JustX*/, true, false),  // pc3: breakc se cmp.x==true (fallthrough==endAddress)
                movWord(TEMP_R0, 0),                    // pc4: fora do laço — o0 = r0
                endWord());                             // pc5

        float[][] input = new float[VertexShaderInterpreter.NUM_INPUT_REGISTERS][4];
        input[0] = new float[]{1f, 1f, 1f, 1f};
        input[1] = new float[]{9f, 9f, 9f, 9f};
        int[][] intConstants = new int[VertexShaderInterpreter.NUM_INT_CONSTANTS][4];
        intConstants[0] = new int[]{4, 0, 1, 0}; // 5 iterações se não houvesse o break

        float[][] output = VertexShaderInterpreter.run(shader, 0, input,
                new float[VertexShaderInterpreter.NUM_FLOAT_CONSTANTS][4], intConstants,
                new boolean[VertexShaderInterpreter.NUM_BOOL_CONSTANTS]);

        assertEquals(1f, output[0][0], 1e-6f, "só 1 iteração deveria ter rodado antes do BREAKC sair do laço");
    }
}
