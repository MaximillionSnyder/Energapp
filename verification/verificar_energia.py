#!/usr/bin/env python3
"""Verificacion numerica del motor de medicion de energia-app.

Reproduce la matematica de EnergyModel.kt:
    E[J] += ((P(t0) + P(t1)) / 2) * dt      regla del trapecio
    P[mW] = I[mA] * V[V]
y la compara con las otras dos fuentes que Android ofrece:
    B) delta del nivel de bateria (resolucion 1 %)
    C) delta del contador de carga del hardware (coulomb counter)

Referencia "verdadera": integracion fina del perfil de carga (paso 0.2 s),
suficiente para resolver los picos de 1 s del perfil.

Uso: python3 verificar_energia.py
"""
import math, random, time
from dataclasses import dataclass

NOMINAL_V, CAP_MAH = 3.87, 5100.0
E_J = CAP_MAH * NOMINAL_V / 1000.0 * 3600.0
BASE_MA = 900.0
DF = 0.2

# Corriente que anade tener la pantalla encendida e interactiva (mA). Un panel
# tipico a brillo medio ronda 200-400 mA a 3,87 V.
SCREEN_MA = 320.0

def load_ma(t, screen_on=True):
    """Perfil de carga: base + deriva lenta + picos de 1 s cada 11 s.

    La componente de pantalla se suma solo con la pantalla encendida: es lo que
    hace que el reparto por estado tenga sentido fisico.
    """
    slow = 120.0*math.sin(2*math.pi*t/600.0) + 60.0*math.cos(2*math.pi*t/180.0)
    spike = 350.0 if abs((t % 11.0) - 5.0) < 0.5 else 0.0
    screen = SCREEN_MA if screen_on else 0.0
    return max(0.0, BASE_MA + slow + spike + screen)

def true_energy_j(t0, t1, screen_on=True):
    """Integra el perfil real con paso fino: esta es la verdad fisica."""
    n = max(1, int((t1-t0)/DF))
    dt = (t1-t0)/n
    tot, prev = 0.0, load_ma(t0, screen_on)/1000.0*NOMINAL_V
    for k in range(1, n+1):
        cur = load_ma(t0+k*dt, screen_on)/1000.0*NOMINAL_V
        tot += (prev+cur)*0.5*dt
        prev = cur
    return tot

def interval_avg_ma(t0, t1, n=8, screen_on=True):
    dt = (t1-t0)/n
    return sum(load_ma(t0+(k+0.5)*dt, screen_on) for k in range(n))/n

@dataclass
class Sample:
    t: float; i_ua: int; lvl_milli: int; cc_uah: int; charging: bool
    screen_on: bool = True

def run(t0, dur, step, mode='avg', seed=3, noise=60.0, screen_on=True):
    """Simula el muestreo de la app. mode: 'now' (instantanea) | 'avg' (media)."""
    rng = random.Random(seed)
    xs, used_ah, t = [], 0.0, t0
    cc0, lvl0 = int(0.62*CAP_MAH*1e6), 62000
    end = t0 + dur
    while t < end:                      # <-- '<' y no '<=': evita el tramo dt=0
        t1 = min(t+step, end)
        if t1 <= t:
            break
        avg = interval_avg_ma(t, t1, screen_on=screen_on)
        base = load_ma(t, screen_on) if mode == 'now' else avg
        nz = noise if mode == 'now' else noise/math.sqrt(step)
        i = max(0.0, base + rng.gauss(0, nz))
        used_ah += avg*(t1-t)/3600.0/1000.0
        xs.append(Sample(t, int(i*1000), int(lvl0 - used_ah/CAP_MAH*1e8),
                         cc0 - int(used_ah*1e6), False, screen_on))
        t = t1
    return xs

def trap_j(xs, gap=1e9):
    """La formula exacta de EnergyModel.kt."""
    tot = 0.0
    for a, b in zip(xs, xs[1:]):
        dt = b.t - a.t
        if dt <= 0 or dt > gap or a.charging or b.charging:
            continue
        tot += ((a.i_ua + b.i_ua)/2.0/1e6*NOMINAL_V)*dt
    return tot

def chg_mah(xs, gap=1e9):
    tot = 0.0
    for a, b in zip(xs, xs[1:]):
        dt = b.t - a.t
        if dt <= 0 or dt > gap or a.charging or b.charging:
            continue
        tot += ((a.i_ua + b.i_ua)/2.0/3.6)*dt/1e6  # uA*s / 3.6 = mAh
    return tot

def counter_j(xs):
    return (xs[0].cc_uah - xs[-1].cc_uah)/1e6*NOMINAL_V*3600.0

def err(e, t):
    return abs(e-t)/t*100.0

def show(label, e, t):
    print(f"  {label:<32}: {e:9.2f} J   error {err(e,t):6.3f} %")

print("="*78)
print("VERIFICACION DEL MOTOR DE MEDICION - energia-app")
print("="*78)
print(f"Bateria de referencia: {CAP_MAH:.0f} mAh @ {NOMINAL_V} V = "
      f"{CAP_MAH*NOMINAL_V/1000:.2f} Wh = {E_J:.0f} J")
print("Perfil: 900 mA base + deriva lenta + picos de 1 s cada 11 s")
print(f"Referencia: integracion del perfil con paso {DF} s\n")

t0_clock = time.time()
DUR = 1800.0
tj = true_energy_j(0.0, DUR)

print(f"ESCENARIO 1: 30 min de uso. Energia REAL = {tj:.2f} J ({tj/E_J*100:.3f} % de bateria)")
print("-"*78)
xs_now = run(0.0, DUR, 5.0, 'now')
xs_avg = run(0.0, DUR, 5.0, 'avg')
show("CURRENT_NOW (instantanea, 5 s)", trap_j(xs_now), tj)
show("CURRENT_AVERAGE (media, 5 s)", trap_j(xs_avg), tj)
show("contador de carga (mismo run)", counter_j(xs_avg), tj)
print(f"  {'carga integrada':<32}: {chg_mah(xs_avg):9.2f} mAh")
print(f"  {'potencia media real':<32}: {tj/DUR*1000:9.2f} mW")
print()

print("ESCENARIO 2: error segun el intervalo de muestreo (clave del diseno)")
print("-"*78)
print(f"  {'Intervalo':>9} | {'CURRENT_NOW':>12} | {'CURRENT_AVERAGE':>15}")
for step in (1, 5, 15, 30, 60, 120):
    en = trap_j(run(0.0, DUR, float(step), 'now'))
    ea = trap_j(run(0.0, DUR, float(step), 'avg'))
    print(f"  {step:>7} s | {err(en,tj):>11.3f} % | {err(ea,tj):>14.3f} %")
print()
print("  Lectura: con CURRENT_AVERAGE (el hardware promedia el intervalo) el")
print("  error se mantiene en ~0,1-2 % hasta 30 s, mejor que la instantanea en")
print("  ese rango. Pasado ~60 s ambos se degradan, porque los picos de 1 s se")
print("  aliasean y ya no hay informacion que promediar. Conclusion de diseno:")
print("  muestrear cada 5-30 s y preferir CURRENT_AVERAGE, con CURRENT_NOW como")
print("  respaldo. Por eso el servicio usa 5 s por defecto.")
print()

D3 = 180.0
t3 = true_energy_j(0.0, D3)
xs3 = run(0.0, D3, 5.0, 'avg')
print(f"ESCENARIO 3: uso corto de 3 min (el caso '10 % en 3 min'). Real = {t3:.2f} J")
print("-"*78)
show("trapecio con CURRENT_AVERAGE", trap_j(xs3), t3)
show("contador de carga (uAh)", counter_j(xs3), t3)
escalon = E_J/100.0
print(f"  {'nivel del sistema (paso 1 %)':<32}: escalon de {escalon:.0f} J -> error >= "
      f"{escalon/2/t3*100:.0f} %  (inutilizable)")
print(f"  {'potencia media real':<32}: {t3/D3*1000:9.2f} mW")
print(f"  {'el 10 %% del sistema exigiria':<32}: {0.10*E_J/D3*1000:9.1f} mW sostenidos")
print()

xs4 = run(0.0, 600.0, 5.0, 'avg')
xs4b = [Sample(x.t, x.i_ua, x.lvl_milli, x.cc_uah, 200 <= x.t < 260, x.screen_on)
        for x in xs4]
xs4b.append(Sample(xs4b[-1].t+600, -1_200_000, 70000, 0, False, False))
print("ESCENARIO 4: robustez (tramo cargando + hueco de 600 s)")
print("-"*78)
print(f"  Total descartando tramos invalidos: {trap_j(xs4b, gap=120.0):.2f} J (no se infla)")
print()

print("ESCENARIO 5: coste del propio monitoreo")
print("-"*78)
for step in (5, 30, 60):
    n = 3600/step; cpu = n*0.001*1.0
    print(f"  cada {step:>2} s -> {n:>4.0f} lecturas/h -> ~{cpu:.3f} J/h "
          f"({cpu/E_J*100:.5f} % de bateria/h)")
print()
# ---------------------------------------------------------------------------
# ESCENARIO 6: atribucion por estado de pantalla (EnergyModel.screenLedger)
# ---------------------------------------------------------------------------
def screen_ledger(xs, gap=1e9):
    """Misma logica que EnergyModel.add: el tramo va al estado del extremo INICIAL."""
    on_j = off_j = 0.0
    on_ms = off_ms = 0
    for a, b in zip(xs, xs[1:]):
        dt = (b.t - a.t)*1000.0
        if dt <= 0 or dt > gap or a.charging or b.charging:
            continue
        p = ((a.i_ua/1000.0) + (b.i_ua/1000.0))/2.0/1000.0*NOMINAL_V*1000.0  # mW
        d_j = p*(dt/1000.0)/1000.0
        if a.screen_on:
            on_j += d_j; on_ms += dt
        else:
            off_j += d_j; off_ms += dt
    return on_j, on_ms, off_j, off_ms

# 20 min: 5 min con pantalla encendida + 15 min apagada, en dos tramos
xs_on = run(0.0, 300.0, 5.0, 'avg', screen_on=True)
xs_off = run(300.0, 900.0, 5.0, 'avg', screen_on=False)
xs_sc = xs_on + xs_off
on_j, on_ms, off_j, off_ms = screen_ledger(xs_sc)
tot = on_j + off_j
print("ESCENARIO 6: reparto por estado de pantalla (5 min encendida + 15 apagada)")
print("-"*78)
print(f"  Pantalla ENCENDIDA : {on_j:8.2f} J en {on_ms/1000:6.0f} s -> {on_j/(on_ms/1000)*1000:7.2f} mW de media")
print(f"  Pantalla APAGADA   : {off_j:8.2f} J en {off_ms/1000:6.0f} s -> {off_j/(off_ms/1000)*1000:7.2f} mW de media")
print(f"  Comprobacion de suma: {(on_j+off_j):.2f} J  vs  trapecio global {trap_j(xs_sc):.2f} J")
global_real = true_energy_j(0.0, 300.0, True) + true_energy_j(300.0, 1200.0, False)
coincide = abs((on_j+off_j) - trap_j(xs_sc)) < 1e-6
print(f"  Ambos metodos coinciden: {'SI' if coincide else 'NO'}  (el reparto no pierde ni inventa energia)")
print(f"  Proporcion apagada : {off_j/tot*100:.1f} % de la energia en {off_ms/(on_ms+off_ms)*100:.0f} % del tiempo")
print(f"  Energia real del tramo completo (integracion fina): {global_real:.2f} J")
print(f"  Error del reparto frente a la realidad             : {err(on_j+off_j, global_real):.3f} %")

print()
print("CONCLUSION")
print("-"*78)
print("La integracion de la corriente del hardware reproduce la energia real con")
print("error <1 % a 5 s y ~2 % a 30 s, y expresa el gasto en J, mAh y % de")
print("bateria. El nivel del sistema (escalones de 1 %) no tiene esa resolucion:")
print("de ahi cifras como '10 % en 3 minutos', que exigirian ~39 W sostenidos.")
print(f"Tiempo de verificacion: {time.time()-t0_clock:.2f} s")
print("="*78)
