import { Routes, UrlMatcher } from '@angular/router';
import { authGuard, guestGuard } from './core/auth/auth.guards';

/**
 * One route for /ask and /ask/:id, so moving between conversations keeps the same page instead of
 * recreating it. That includes the move to a conversation's address after its first answer.
 */
export const askUrl: UrlMatcher = (segments) => {
  if (segments.length === 0 || segments.length > 2 || segments[0].path !== 'ask') {
    return null;
  }
  return segments.length === 2
    ? { consumed: segments, posParams: { id: segments[1] } }
    : { consumed: segments };
};

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    title: 'DocChat AI',
    loadComponent: () => import('./features/home/home').then((m) => m.Home),
  },
  {
    path: 'login',
    title: 'Sign in - DocChat AI',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/login/login').then((m) => m.Login),
  },
  {
    path: 'register',
    title: 'Create account - DocChat AI',
    canActivate: [guestGuard],
    loadComponent: () => import('./features/auth/register/register').then((m) => m.Register),
  },
  {
    path: 'documents',
    title: 'Documents - DocChat AI',
    canActivate: [authGuard],
    loadComponent: () => import('./features/documents/documents').then((m) => m.Documents),
  },
  {
    matcher: askUrl,
    title: 'Ask - DocChat AI',
    canActivate: [authGuard],
    loadComponent: () => import('./features/ask/ask').then((m) => m.Ask),
  },
  { path: '**', redirectTo: '' },
];
