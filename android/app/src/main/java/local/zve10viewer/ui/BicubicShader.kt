package local.zve10viewer.ui

/**
 * Бикубическая интерполяция Catmull-Rom (B = 0, C = 0,5) на AGSL: 4×4 точки кадра за 9 билинейных выборок —
 * у BitmapShader с FILTER_MODE_LINEAR одна выборка между двумя точками даёт их взвешенную сумму, и средние два веса
 * каждой оси сливаются в одну выборку со смещённой координатой. Билинейная растяжка 1024×576 на экран 2560×1600 (и
 * до 20 раз при 8×) — мыло и «ромбы»; Catmull-Rom заметно чётче на краях, а небольшой выброс за 0…1 срезается.
 *
 * origin — где на экране левый верхний угол кадра, scale — точек кадра на точку экрана. Координаты выборок у дочернего
 * шейдера — в точках кадра, центры точек — на +0,5.
 */
const val BICUBIC_AGSL = """
uniform shader image;
uniform float2 origin;
uniform float2 scale;

half4 main(float2 coord) {
    float2 p = (coord - origin) * scale;
    float2 t1 = floor(p - 0.5) + 0.5;
    float2 f = p - t1;
    float2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    float2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    float2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    float2 w3 = f * f * (-0.5 + 0.5 * f);
    float2 w12 = w1 + w2;
    float2 t0 = t1 - 1.0;
    float2 t3 = t1 + 2.0;
    float2 t12 = t1 + w2 / w12;
    half4 c = image.eval(float2(t0.x, t0.y)) * half(w0.x * w0.y)
        + image.eval(float2(t12.x, t0.y)) * half(w12.x * w0.y)
        + image.eval(float2(t3.x, t0.y)) * half(w3.x * w0.y)
        + image.eval(float2(t0.x, t12.y)) * half(w0.x * w12.y)
        + image.eval(float2(t12.x, t12.y)) * half(w12.x * w12.y)
        + image.eval(float2(t3.x, t12.y)) * half(w3.x * w12.y)
        + image.eval(float2(t0.x, t3.y)) * half(w0.x * w3.y)
        + image.eval(float2(t12.x, t3.y)) * half(w12.x * w3.y)
        + image.eval(float2(t3.x, t3.y)) * half(w3.x * w3.y);
    return clamp(c, half4(0.0), half4(1.0));
}
"""
