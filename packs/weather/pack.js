// Weather pack: Open-Meteo geocoding and forecast. No key, no secrets.
const GEO = 'https://geocoding-api.open-meteo.com/v1/search';
const API = 'https://api.open-meteo.com/v1/forecast';

const CODES = {
  0: 'clear sky', 1: 'mostly clear', 2: 'partly cloudy', 3: 'overcast', 45: 'fog', 48: 'freezing fog',
  51: 'light drizzle', 53: 'drizzle', 55: 'heavy drizzle', 56: 'freezing drizzle', 57: 'heavy freezing drizzle',
  61: 'light rain', 63: 'rain', 65: 'heavy rain', 66: 'freezing rain', 67: 'heavy freezing rain',
  71: 'light snow', 73: 'snow', 75: 'heavy snow', 77: 'snow grains', 80: 'light showers', 81: 'showers', 82: 'heavy showers',
  85: 'light snow showers', 86: 'snow showers', 95: 'thunderstorm', 96: 'thunderstorm with hail', 99: 'severe thunderstorm with hail',
};
const words = (code) => CODES[code] || 'unknown conditions';

async function getJson(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error('The weather service answered ' + r.status);
  return r.json();
}

async function locate(place) {
  const name = String(place || '').trim();
  if (!name) throw new Error('Say which place you mean');
  const j = await getJson(`${GEO}?name=${encodeURIComponent(name)}&count=1&language=en&format=json`);
  const hit = j.results && j.results[0];
  if (!hit) throw new Error(`I could not find a place called "${name}"`);
  return { name: [hit.name, hit.admin1, hit.country].filter(Boolean).join(', '), lat: hit.latitude, lon: hit.longitude };
}

const round = (n) => (typeof n === 'number' ? Math.round(n * 10) / 10 : n);

pack = {
  tools: {
    'weather.now': async ({ place }) => {
      const p = await locate(place);
      const j = await getJson(`${API}?latitude=${p.lat}&longitude=${p.lon}&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m&timezone=auto`);
      const c = j.current;
      return {
        place: p.name, conditions: words(c.weather_code), temperatureC: round(c.temperature_2m), feelsLikeC: round(c.apparent_temperature),
        humidityPercent: c.relative_humidity_2m, windKmh: round(c.wind_speed_10m), localTime: c.time,
      };
    },
    'weather.forecast': async ({ place, days }) => {
      const n = Math.min(7, Math.max(1, Math.round(Number(days) || 3)));
      const p = await locate(place);
      const j = await getJson(`${API}?latitude=${p.lat}&longitude=${p.lon}&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=${n}`);
      const d = j.daily;
      return {
        place: p.name,
        days: d.time.map((date, i) => ({
          date, conditions: words(d.weather_code[i]), highC: round(d.temperature_2m_max[i]), lowC: round(d.temperature_2m_min[i]),
          chanceOfRainPercent: d.precipitation_probability_max[i],
        })),
      };
    },
  },
};
