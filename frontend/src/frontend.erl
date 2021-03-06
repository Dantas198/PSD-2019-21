-module(frontend).

-include("protos/protos.hrl").

%% API exports
-export([main/1]).


%%====================================================================
%% API functions
%%====================================================================
mod(X,Y) when X > 0 -> X rem Y;
mod(X,Y) when X < 0 -> Y + X rem Y;
mod(0,Y) -> 0.


start_worker(Identity, Destination, Port, Parent) ->
    {ok, Socket} = chumak:socket(dealer, Identity),
    {ok, _PeerPid} = chumak:bind(Socket, tcp, "localhost", Port),
    spawn(fun() -> worker_loop_recv(Socket, Parent) end),
    Iden = list_to_binary(Identity),
    worker_loop_send(Socket, Iden, Destination).


worker_loop_send(Socket, Identity, Destination) ->
    %uma de cada vez
    receive
        {ClientIdentity, Message} ->
            %novo processo para não empancar tudo à espera da resposta
            spawn(fun() -> chumak:send_multipart(Socket, [Destination, Identity, ClientIdentity, <<>>, Message]) end);
        _ -> io:format("~p~n", ["invalido"])
    end,
    worker_loop_send(Socket, Identity, Destination).


worker_loop_recv(Socket, Parent) ->
    %uma de cada vez
    {ok, Multipart} = chumak:recv_multipart(Socket),
    io:format("~p~p~n", ["Worker Received", Multipart]),
    Parent ! {answer, Multipart},
    worker_loop_recv(Socket, Parent).

%%processo que se encarrega de receber pedidos de clientes
request_loop(Socket, Parent, Workers, N) ->
    {ok, [Identity, <<>>, Message]} = chumak:recv_multipart(Socket),
    io:format("~p~p~p~n", ["Request arrived", Identity, Message]),
    %%gera número aleatório
    case N of
        0 -> Worker = maps:get("A", Workers);
        1 -> Worker = maps:get("B", Workers)
        %2 -> Worker = maps:get("C", Workers)
    end,
    Worker ! {Identity, Message},
    R = mod(N+1, 2),
    request_loop(Socket, Parent, Workers, R). 

%encarrega-se de devolver respostas aos clientes
reply_loop(Socket) -> 
    receive
        {answer, [_, _, ClientIdentity, <<>>, Message]} ->
            io:format("~p~p~p~n", ["reply", ClientIdentity, Message]),
            ok = chumak:send_multipart(Socket, [ClientIdentity, <<>>, Message])
    end,
    reply_loop(Socket).

monitorReqRep(ReplyRef, RequestsRef, Socket, Reply, Workers) ->
    receive
        {'DOWN', ReplyRef, process, Pid, R} ->
            io:format("Server ~p down: ~p~n", [Pid, R]),
            NewReplyRef = erlang:monitor(process, spawn(fun() -> reply_loop(Socket) end)),
            monitorReqRep(NewReplyRef, RequestsRef, Socket, Reply, Workers);
        {'DOWN', RequestRef, process, Pid, R} ->
            io:format("Client ~p down: ~p~n", [Pid, R]),
            NewRequestsRef = erlang:monitor(process, spawn(fun() -> request_loop(Socket, Reply, Workers, 0) end)),
            monitorReqRep(ReplyRef, NewRequestsRef, Socket, Reply, Workers)
    end.

main(Args) ->
    io:format("~p~n", [Args]),
    application:ensure_started(chumak),
    {ok, Socket} = chumak:socket(router),
    {ok, _BindPid} = chumak:bind(Socket, tcp, "localhost", 5555),
    Reply = spawn(fun() -> reply_loop(Socket) end),    
    W1 = spawn(fun() -> start_worker("A", <<"X">>, 5556, Reply) end),
    W2 = spawn(fun() -> start_worker("B", <<"Y">>, 5557, Reply) end),
    %W3 = spawn(fun() -> start_worker("C", <<"Z">>, 5558, Reply) end),
    Workers=#{"A" => W1,
              "B" => W2},
              %"C" => W3
    Requests = spawn(fun() -> request_loop(Socket, Reply, Workers, 0) end),
    %%monitors
    RequestsRef =  erlang:monitor(process, Requests),
    ReplyRef = erlang:monitor(process, Reply),
    %spawn(fun() -> pub_sub() end),
    monitorReqRep(ReplyRef, RequestsRef, Socket, Reply, Workers).

%%====================================================================
%% Internal functions
%%====================================================================

pub_sub()->
    {ok, Sub} = chumak:socket(xsub),
    {ok, _PeerPid} = chumak:bind(Sub, tcp, "localhost", 6666),
    chumak:subscribe(Sub, "frontend"),
    {ok, Pub} = chumak:socket(xpub),
    {ok, _PeerPid2} = chumak:bind(Pub, tcp, "localhost", 6667),
    pub_sub_loop(Pub,Sub).
    
pub_sub_loop(Pub, Sub)->
    spawn(fun() -> {ok, Data} = chumak:recv_multipart(Sub),
                    io:format("~p~n", [Data]) end),
    chumak:send(Pub, <<"frontend", "OI">>),
    %humak:send_multipart(Pub, Data),
    pub_sub_loop(Pub,Sub).
