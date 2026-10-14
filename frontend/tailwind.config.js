/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        nexa: {
          navy: {
            950: '#060D1A',
            900: '#0A192F',
            850: '#0F2342',
            800: '#172A45',
            700: '#233554',
          },
          blue: {
            700: '#1D4ED8',
            600: '#2563EB',
            500: '#3B82F6',
            400: '#60A5FA',
          },
          teal: {
            700: '#0F766E',
            600: '#0D9488',
            500: '#14B8A6',
            400: '#2DD4BF',
          },
          slate: {
            900: '#0F172A',
            800: '#1E293B',
            700: '#334155',
            600: '#475569',
            400: '#94A3B8',
            200: '#E2E8F0',
            100: '#F1F5F9',
            50: '#F8FAFC',
          }
        }
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', '-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'Roboto', 'sans-serif'],
      }
    },
  },
  plugins: [],
}
