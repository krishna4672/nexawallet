import React from 'react';
import { Link } from 'react-router-dom';
import {
  ShieldCheck,
  Zap,
  Lock,
  Layers,
  Search,
  MapPin,
  ArrowRight,
  Database,
  Cpu,
  CheckCircle2,
  AlertTriangle,
  Code2
} from 'lucide-react';

export const LandingPage: React.FC = () => {
  return (
    <div className="space-y-24 py-12 px-4 sm:px-6 lg:px-8 max-w-7xl mx-auto">
      {/* Hero Section */}
      <section className="text-center space-y-6 pt-6">
        <div className="inline-flex items-center space-x-2 px-4 py-1.5 rounded-full text-xs font-semibold bg-nexa-blue-500/10 border border-nexa-blue-500/30 text-nexa-blue-400 mb-2">
          <Zap className="w-3.5 h-3.5 text-nexa-teal-400" />
          <span>Java 21 • Spring Boot 3 • High-Throughput Fintech Engine</span>
        </div>

        <h1 className="text-4xl sm:text-6xl font-extrabold tracking-tight text-white max-w-4xl mx-auto leading-tight sm:leading-none">
          Mission-Critical <span className="text-transparent bg-clip-text bg-gradient-to-r from-nexa-blue-400 via-nexa-teal-400 to-emerald-400">Digital Wallet</span> Architecture
        </h1>

        <p className="text-base sm:text-xl text-slate-300 max-w-2xl mx-auto leading-relaxed">
          Engineered for senior Java recruiter evaluations. Featuring a balanced double-entry ledger, deterministic lock ordering, database-backed idempotency, and a transactional outbox Kafka pipeline.
        </p>

        {/* Call to Actions */}
        <div className="flex flex-col sm:flex-row items-center justify-center gap-4 pt-4">
          <Link
            to="/dashboard"
            className="w-full sm:w-auto inline-flex items-center justify-center space-x-2 px-6 py-3.5 rounded-xl font-semibold bg-gradient-to-r from-nexa-blue-600 to-nexa-teal-600 text-white shadow-xl shadow-nexa-blue-600/30 hover:from-nexa-blue-500 hover:to-nexa-teal-500 transition-all duration-200"
          >
            <span>Launch App Preview</span>
            <ArrowRight className="w-4 h-4" />
          </Link>
          <Link
            to="/register"
            className="w-full sm:w-auto inline-flex items-center justify-center space-x-2 px-6 py-3.5 rounded-xl font-semibold bg-nexa-navy-900 border border-nexa-navy-700 text-slate-200 hover:text-white hover:bg-nexa-navy-800 transition-all duration-200"
          >
            <ShieldCheck className="w-4 h-4 text-nexa-teal-400" />
            <span>Create Test Account</span>
          </Link>
          <a
            href="http://localhost:8080/swagger-ui.html"
            target="_blank"
            rel="noopener noreferrer"
            className="w-full sm:w-auto inline-flex items-center justify-center space-x-2 px-6 py-3.5 rounded-xl font-semibold bg-nexa-navy-900/60 border border-nexa-navy-800 text-slate-300 hover:text-white transition-all duration-200"
          >
            <Code2 className="w-4 h-4 text-slate-400" />
            <span>Swagger API</span>
          </a>
        </div>

        {/* Phase 1 Scaffold Disclaimer */}
        <div className="max-w-2xl mx-auto mt-6 p-3.5 rounded-xl bg-amber-500/10 border border-amber-500/30 text-xs text-amber-200/90 flex items-center justify-center space-x-2">
          <AlertTriangle className="w-4 h-4 text-amber-400 flex-shrink-0" />
          <span>
            <strong>Phase 1 Foundation Scaffold:</strong> Database schema, Flyway migrations, Docker Compose stack, and frontend shells are initialized. Live financial mutations and JWT auth connect in Phase 2 & 3.
          </span>
        </div>
      </section>

      {/* Fintech Architecture Deep-Dive Feature Highlight */}
      <section className="p-8 rounded-3xl bg-nexa-navy-900/90 border border-nexa-navy-800 shadow-2xl relative overflow-hidden">
        <div className="absolute top-0 right-0 w-96 h-96 bg-nexa-blue-600/10 rounded-full blur-3xl pointer-events-none" />
        <div className="relative z-10 space-y-6">
          <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
            <div>
              <span className="text-xs font-mono uppercase tracking-wider text-nexa-teal-400 font-semibold">
                Fintech Engineering Rigor
              </span>
              <h2 className="text-2xl sm:text-3xl font-bold text-white mt-1">
                How NexaWallet Solves Industry-Standard Concurrency Bugs
              </h2>
            </div>
            <span className="inline-flex items-center space-x-1.5 px-3 py-1 rounded-full text-xs font-mono bg-nexa-navy-950 border border-nexa-navy-800 text-slate-300">
              <Database className="w-3.5 h-3.5 text-nexa-blue-400" />
              <span>PostgreSQL 16 Engine</span>
            </span>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-6 pt-2">
            {/* Column 1 */}
            <div className="p-5 rounded-2xl bg-nexa-navy-950/70 border border-nexa-navy-800/80 space-y-3">
              <div className="w-9 h-9 rounded-lg bg-red-500/10 border border-red-500/20 flex items-center justify-center text-red-400 font-bold text-sm">
                Bug 1
              </div>
              <h3 className="font-semibold text-white">Cross-Transfer Deadlocks</h3>
              <p className="text-xs text-slate-400 leading-relaxed">
                <strong>The Risk:</strong> Alice sends $50 to Bob while Bob sends $30 to Alice simultaneously. Naive row locking causes circular deadlock and aborted transactions.
              </p>
              <div className="pt-2 text-xs text-nexa-teal-300 flex items-center space-x-1.5">
                <CheckCircle2 className="w-4 h-4 text-nexa-teal-400 flex-shrink-0" />
                <span><strong>Nexa Solution:</strong> Lexicographical Deterministic Lock Ordering on Wallet UUIDs.</span>
              </div>
            </div>

            {/* Column 2 */}
            <div className="p-5 rounded-2xl bg-nexa-navy-950/70 border border-nexa-navy-800/80 space-y-3">
              <div className="w-9 h-9 rounded-lg bg-amber-500/10 border border-amber-500/20 flex items-center justify-center text-amber-400 font-bold text-sm">
                Bug 2
              </div>
              <h3 className="font-semibold text-white">Network Retry Double Spend</h3>
              <p className="text-xs text-slate-400 leading-relaxed">
                <strong>The Risk:</strong> Mobile connection drops after server execution. Client retries the HTTP call, charging the sender twice.
              </p>
              <div className="pt-2 text-xs text-nexa-teal-300 flex items-center space-x-1.5">
                <CheckCircle2 className="w-4 h-4 text-nexa-teal-400 flex-shrink-0" />
                <span><strong>Nexa Solution:</strong> Durable Database Idempotency table guarding against duplicate keys.</span>
              </div>
            </div>

            {/* Column 3 */}
            <div className="p-5 rounded-2xl bg-nexa-navy-950/70 border border-nexa-navy-800/80 space-y-3">
              <div className="w-9 h-9 rounded-lg bg-blue-500/10 border border-blue-500/20 flex items-center justify-center text-blue-400 font-bold text-sm">
                Bug 3
              </div>
              <h3 className="font-semibold text-white">Dual-Write Distributed Discrepancy</h3>
              <p className="text-xs text-slate-400 leading-relaxed">
                <strong>The Risk:</strong> Transfer commits to PostgreSQL, but network drops before Kafka receives message, permanently desyncing search indexes.
              </p>
              <div className="pt-2 text-xs text-nexa-teal-300 flex items-center space-x-1.5">
                <CheckCircle2 className="w-4 h-4 text-nexa-teal-400 flex-shrink-0" />
                <span><strong>Nexa Solution:</strong> Transactional Outbox pattern with background scheduled Kafka publisher.</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* Core Architectural Pillars */}
      <section className="space-y-8">
        <div className="text-center space-y-2">
          <span className="text-xs font-mono uppercase tracking-wider text-nexa-teal-400 font-semibold">
            Modular Platform Features
          </span>
          <h2 className="text-3xl font-bold text-white">Full-Stack Tech Stack Highlights</h2>
          <p className="text-slate-400 text-sm max-w-xl mx-auto">
            Engineered across backend data layers, streaming pipelines, caching, and modern frontend interfaces.
          </p>
        </div>

        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <div className="p-6 rounded-2xl bg-nexa-navy-900/60 border border-nexa-navy-800 hover:border-nexa-blue-500/40 transition-colors space-y-3">
            <div className="w-10 h-10 rounded-xl bg-nexa-blue-600/20 text-nexa-blue-400 flex items-center justify-center">
              <Layers className="w-5 h-5" />
            </div>
            <h3 className="text-lg font-bold text-white">Double-Entry Ledger</h3>
            <p className="text-xs text-slate-400 leading-relaxed">
              Every financial event records immutable debit and credit pairs. Check constraints guarantee non-negative wallet balances and mathematical equilibrium.
            </p>
          </div>

          <div className="p-6 rounded-2xl bg-nexa-navy-900/60 border border-nexa-navy-800 hover:border-nexa-teal-500/40 transition-colors space-y-3">
            <div className="w-10 h-10 rounded-xl bg-nexa-teal-600/20 text-nexa-teal-400 flex items-center justify-center">
              <Cpu className="w-5 h-5" />
            </div>
            <h3 className="text-lg font-bold text-white">Apache Kafka (KRaft)</h3>
            <p className="text-xs text-slate-400 leading-relaxed">
              Event-driven architecture without Zookeeper. Asynchronously feeds consumer pipelines for transaction indexing, customer alerts, and audit logging.
            </p>
          </div>

          <div className="p-6 rounded-2xl bg-nexa-navy-900/60 border border-nexa-navy-800 hover:border-nexa-blue-500/40 transition-colors space-y-3">
            <div className="w-10 h-10 rounded-xl bg-nexa-blue-600/20 text-nexa-blue-400 flex items-center justify-center">
              <Search className="w-5 h-5" />
            </div>
            <h3 className="text-lg font-bold text-white">Elasticsearch 8</h3>
            <p className="text-xs text-slate-400 leading-relaxed">
              Real-time faceted full-text transaction queries by counterparty name, memo, amount ranges, and dates with automated Kafka consumer syncing.
            </p>
          </div>

          <div className="p-6 rounded-2xl bg-nexa-navy-900/60 border border-nexa-navy-800 hover:border-nexa-teal-500/40 transition-colors space-y-3">
            <div className="w-10 h-10 rounded-xl bg-nexa-teal-600/20 text-nexa-teal-400 flex items-center justify-center">
              <Lock className="w-5 h-5" />
            </div>
            <h3 className="text-lg font-bold text-white">Redis 7 Rate Limiter</h3>
            <p className="text-xs text-slate-400 leading-relaxed">
              High-throughput sliding-window rate limiting protects auth routes and money transfers against automated abuse, brute force, and DDoS.
            </p>
          </div>

          <div className="p-6 rounded-2xl bg-nexa-navy-900/60 border border-nexa-navy-800 hover:border-nexa-blue-500/40 transition-colors space-y-3">
            <div className="w-10 h-10 rounded-xl bg-nexa-blue-600/20 text-nexa-blue-400 flex items-center justify-center">
              <MapPin className="w-5 h-5" />
            </div>
            <h3 className="text-lg font-bold text-white">Geohash Proximity</h3>
            <p className="text-xs text-slate-400 leading-relaxed">
              Base32 Geohash proximity engine with 8-neighbor bounding box calculations to find nearby ATMs and service points without edge-boundary omissions.
            </p>
          </div>

          <div className="p-6 rounded-2xl bg-nexa-navy-900/60 border border-nexa-navy-800 hover:border-nexa-teal-500/40 transition-colors space-y-3">
            <div className="w-10 h-10 rounded-xl bg-nexa-teal-600/20 text-nexa-teal-400 flex items-center justify-center">
              <ShieldCheck className="w-5 h-5" />
            </div>
            <h3 className="text-lg font-bold text-white">Spring Security & JWT</h3>
            <p className="text-xs text-slate-400 leading-relaxed">
              Stateless token lifecycle with HMAC-SHA512 access tokens, database refresh token rotation, and resource ownership method security.
            </p>
          </div>
        </div>
      </section>

      {/* Tech Stack Pills Bar */}
      <section className="text-center pt-8 border-t border-nexa-navy-800">
        <p className="text-xs font-semibold uppercase tracking-wider text-slate-400 mb-4">
          Strictly Pinned Production-Grade Stack
        </p>
        <div className="flex flex-wrap items-center justify-center gap-2">
          {['Java 21', 'Spring Boot 3.3', 'PostgreSQL 16', 'Flyway', 'Apache Kafka (KRaft)', 'Redis 7', 'Elasticsearch 8', 'React 18', 'TypeScript', 'Vite', 'Tailwind CSS', 'Recharts', 'Docker Compose', 'Testcontainers'].map((tech) => (
            <span
              key={tech}
              className="px-3 py-1 rounded-lg text-xs font-mono font-medium bg-nexa-navy-900 border border-nexa-navy-800 text-slate-300"
            >
              {tech}
            </span>
          ))}
        </div>
      </section>
    </div>
  );
};
