import { type ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ErrorBoundary } from '@/components/error-boundary';
import { Toaster } from '@/components/ui/toaster';
import { TooltipProvider } from '@/components/ui/tooltip';
import NotFound from '@/pages/not-found';
import {
  AdminClansPage, AdminLoginPage, AdminPage, AdminProductsPage, CallbackPage,
  CataloguePage, CheckoutPage, ClansPage, HomePage, LegalPage, LookupPage,
  ProductPage, StorePage,
} from '@/pages/store-pages';
import { Link, Route, Switch, useLocation, Router as WouterRouter } from 'wouter';

const queryClient = new QueryClient();

function Header() {
  return <>
    <div className="kente-stripe" />
    <header className="border-b border-card-border">
      <nav className="page-shell flex min-h-[68px] flex-wrap items-center justify-between gap-4 py-3">
        <Link href="/" className="text-xl font-extrabold tracking-tight"><span className="text-gr-red">Ghana</span><span className="text-gr-gold">Realms</span></Link>
        <div className="flex flex-wrap gap-x-5 gap-y-2 text-xs font-semibold sm:text-sm">
          <Link href="/store" className="hover:text-gr-gold">Store</Link>
          <Link href="/catalogue" className="hover:text-gr-gold">Catalogue</Link>
          <Link href="/clans" className="hover:text-gr-gold">Clans</Link>
          <Link href="/lookup" className="hover:text-gr-gold">Order Lookup</Link>
        </div>
      </nav>
    </header>
  </>;
}
function Footer() {
  return <footer className="mt-20 border-t border-card-border py-8">
    <div className="page-shell flex flex-col justify-between gap-4 text-xs subtle md:flex-row md:items-center">
      <p>© {new Date().getFullYear()} GhanaRealms. Not affiliated with Mojang or Microsoft.</p>
      <div className="flex flex-wrap gap-x-5 gap-y-2"><Link href="/terms" className="hover:text-gr-gold">Terms</Link><Link href="/privacy" className="hover:text-gr-gold">Privacy</Link><Link href="/refund-policy" className="hover:text-gr-gold">Refunds</Link><Link href="/admin/login" className="hover:text-gr-gold">Admin</Link></div>
    </div>
  </footer>;
}
function Router() {
  return <RoutedErrorBoundary><Header/><Switch>
    <Route path="/" component={HomePage}/>
    <Route path="/store" component={StorePage}/>
    <Route path="/store/:slug" component={ProductPage}/>
    <Route path="/catalogue" component={CataloguePage}/>
    <Route path="/clans" component={ClansPage}/>
    <Route path="/checkout" component={CheckoutPage}/>
    <Route path="/payment/callback" component={CallbackPage}/>
    <Route path="/lookup" component={LookupPage}/>
    <Route path="/admin/login" component={AdminLoginPage}/>
    <Route path="/admin" component={AdminPage}/>
    <Route path="/admin/products" component={AdminProductsPage}/>
    <Route path="/admin/clans" component={AdminClansPage}/>
    <Route path="/terms">{()=><LegalPage type="terms"/>}</Route>
    <Route path="/privacy">{()=><LegalPage type="privacy"/>}</Route>
    <Route path="/refund-policy">{()=><LegalPage type="refund"/>}</Route>
    <Route component={NotFound}/>
  </Switch><Footer/></RoutedErrorBoundary>;
}
function RoutedErrorBoundary({ children }: { children: ReactNode }) {
  const [location] = useLocation();
  return <ErrorBoundary resetKey={location}>{children}</ErrorBoundary>;
}
function App() {
  return <QueryClientProvider client={queryClient}><TooltipProvider><WouterRouter base={import.meta.env.BASE_URL.replace(/\/$/, '')}><Router/></WouterRouter><Toaster/></TooltipProvider></QueryClientProvider>;
}

export default App;
