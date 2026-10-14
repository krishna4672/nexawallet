import React, { useState } from 'react';
import { Outlet } from 'react-router-dom';
import { Navbar } from './Navbar';
import { Sidebar } from './Sidebar';
import { Menu, X } from 'lucide-react';

interface AppLayoutProps {
  showSidebar?: boolean;
}

export const AppLayout: React.FC<AppLayoutProps> = ({ showSidebar = true }) => {
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);

  return (
    <div className="min-h-screen flex flex-col bg-nexa-navy-950 text-slate-100">
      <Navbar />

      <div className="flex-1 flex flex-col md:flex-row relative">
        {/* Mobile Sidebar Toggle Button */}
        {showSidebar && (
          <div className="md:hidden flex items-center justify-between px-4 py-3 bg-nexa-navy-900 border-b border-nexa-navy-800">
            <button
              onClick={() => setMobileMenuOpen(!mobileMenuOpen)}
              className="flex items-center space-x-2 text-sm font-medium text-slate-300 hover:text-white"
            >
              {mobileMenuOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
              <span>{mobileMenuOpen ? 'Close Menu' : 'Platform Menu'}</span>
            </button>
            <span className="text-xs font-mono text-nexa-teal-400 font-medium">NexaWallet</span>
          </div>
        )}

        {/* Desktop Sidebar */}
        {showSidebar && (
          <div className="hidden md:block">
            <Sidebar />
          </div>
        )}

        {/* Mobile Sidebar Modal/Drawer */}
        {showSidebar && mobileMenuOpen && (
          <div className="fixed inset-0 z-40 md:hidden bg-black/60 backdrop-blur-sm flex">
            <div className="w-72 max-w-[80%] bg-nexa-navy-900 h-full shadow-2xl">
              <div className="p-4 border-b border-nexa-navy-800 flex justify-between items-center">
                <span className="font-bold text-white">NexaWallet Menu</span>
                <button
                  onClick={() => setMobileMenuOpen(false)}
                  className="p-1 rounded-lg text-slate-400 hover:text-white"
                >
                  <X className="w-5 h-5" />
                </button>
              </div>
              <Sidebar onClose={() => setMobileMenuOpen(false)} />
            </div>
            <div className="flex-1" onClick={() => setMobileMenuOpen(false)} />
          </div>
        )}

        {/* Main View Area */}
        <main className="flex-1 overflow-y-auto">
          <Outlet />
        </main>
      </div>
    </div>
  );
};
