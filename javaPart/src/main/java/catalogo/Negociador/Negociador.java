package catalogo.Negociador;

import Logic.Encomenda;
import Logic.Producao;
import Logic.Utilizador;
import ProtoBuffers.Protos.LoginResponse;
import ProtoBuffers.Protos.OperationResponse;
import ProtoBuffers.Protos.OperationRequest;
import ProtoBuffers.Protos.OfertaProducaoRequest;
import ProtoBuffers.Protos.OfertaEncomendaRequest;
import ProtoBuffers.Protos.NotificacaoResultadosFabricante;
import ProtoBuffers.Protos.NotificacaoResultadosImportador;
import ProtoBuffers.Protos.NotificacaoOfertaProducao;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;

public class Negociador {

    private final JsonHttpClient jhc = new JsonHttpClient("localhost:12345");
    private ZMQ.Socket socketPUB;
    private String identity;

    public Negociador() {}

    public static void main(String[] args) {
        new Negociador().start("tcp://localhost:5557", "tcp://localhost:6667", "Y");
    }

    public void start(String addrREP, String addrPUB, String identity) {
        try (ZContext context = new ZContext()) {
            this.identity = identity;
            System.out.println("REP: " + addrREP + ", PUB: " + addrPUB);
            ZMQ.Socket socketREP = context.createSocket(ZMQ.REP);
            socketREP.connect(addrREP);
            socketREP.setIdentity(identity.getBytes(ZMQ.CHARSET));
            socketPUB = context.createSocket(ZMQ.PUB);
            socketPUB.bind(addrPUB);

            OperationResponse reply;
            while (!Thread.currentThread().isInterrupted()) {
                reply = REPLY_OK;
                byte[] data = socketREP.recv(0);
                try {
                    System.out.println("\n=========================================================");
                    System.out.println(identity + "\n");
                    OperationRequest request = OperationRequest.parseFrom(data);
                    String nome = request.getNome();
                    String password = request.getPassword();
                    Utilizador utilizador = jhc.getObject("utilizadores/" + nome, Utilizador.class);
                    System.out.print("Recebe de " + nome + ": ");
                    switch (request.getRequestCase().getNumber()){
                        case 0:
                            System.out.println("Login");
                            LoginResponse.TipoUtilizador tipo = LoginResponse.TipoUtilizador.ERRO;
                            if(utilizador == null || !utilizador.getPassword().equals(password)){
                                System.out.println("Credenciais incorretas");
                            } else if(utilizador.getTipo().equals("fabricante")){
                                tipo = LoginResponse.TipoUtilizador.FABRICANTE;
                                System.out.println("Sucesso -> Fabricante");
                            } else if(utilizador.getTipo().equals("importador")){
                                tipo = LoginResponse.TipoUtilizador.IMPORTADOR;
                                System.out.println("Sucesso -> Importador");
                            }
                            LoginResponse response = LoginResponse.newBuilder()
                                    .setTipo(tipo)
                                    .build();
                            socketREP.send(response.toByteArray(), 0);
                            continue;
                        case OperationRequest.PRODUCAO_FIELD_NUMBER: // OFERTA DE PRODUÇÃO
                            if(utilizador == null || !utilizador.getPassword().equals(password)){
                                System.out.print("Credenciais incorretas");
                                reply = REPLY_INVALID;
                                break;
                            }
                            System.out.println("Oferta de produção");
                            OfertaProducaoRequest prodRequest = request.getProducao();
                            Producao producao = Producao.fromProtoRequest(request);
                            System.out.println(producao);

                            reply = processProducao(producao);
                            if(reply != REPLY_OK) break;

                            NotificacaoOfertaProducao notif = prodRequestToNotif(prodRequest);
                            socketPUB.sendMore(nome);
                            socketPUB.send(notif.toByteArray());
                            System.out.println("Envia notificação oferta produção, tópico: " + nome);

                            // cria thread que dorme até acabar o período de negociação
                            // quando acorda determina encomendas aceites e notifica participantes
                            long waitSeconds = prodRequest.getDuracaoS();
                            System.out.println("Começa período de negociação, duração: " + waitSeconds + "s");
                            periodoNegociacao(waitSeconds, producao, nome).start();
                            break;
                        case OperationRequest.ENCOMENDA_FIELD_NUMBER: // OFERTA DE ENCOMENDA
                            if(utilizador == null || !utilizador.getPassword().equals(password)){
                                System.out.print("Credenciais incorretas");
                                reply = REPLY_INVALID;
                                break;
                            }
                            System.out.println("Oferta de encomenda");
                            Encomenda encomenda = Encomenda.fromProtoRequest(request);
                            System.out.println(encomenda);
                            reply = processEncomenda(encomenda);
                            break;
                    }
                } catch (IOException | InterruptedException e) {
                    System.err.println(e.toString());
                    reply = REPLY_INVALID;
                }
                socketREP.send(reply.toByteArray(), 0);
                System.out.println("Enviou resposta: " + reply.getCode());
            }
        }
    }

    private static final OperationResponse REPLY_OK = OperationResponse.newBuilder()
            .setCode(OperationResponse.ResponseStatusCode.OK)
            .build();

    private static final OperationResponse REPLY_INVALID = OperationResponse.newBuilder()
            .setCode(OperationResponse.ResponseStatusCode.INVALID)
            .build();

    private OperationResponse processProducao(Producao producao) throws IOException, InterruptedException {
        int statusCode = jhc.post("producoes/" + producao.getNomeFabricante() + "/" + producao.getNomeProduto(), producao);
        if (statusCode >= 200 && statusCode < 300) {
            return REPLY_OK;
        }
        return REPLY_INVALID;
    }

    private OperationResponse processEncomenda(Encomenda encomenda) throws IOException, InterruptedException {
        Producao prod = jhc.getObject("producoes/" + encomenda.getNomeFabricante() + "/" + encomenda.getNomeProduto(), Producao.class);
        if(prod != null) {
            System.out.println(prod);
            if(encomenda.getPrecoPorUnidade() < prod.getPrecoPorUnidade()) {
                System.out.println("Oferta não atingiu preço mínimo");
                return REPLY_INVALID;
            }
            if(encomenda.getQuantidade() > prod.getQuantidadeMax()) {
                System.out.println("Oferta tem quantidade demasiado grande");
                return REPLY_INVALID;
            }
            System.out.println("Oferta OK");
            int statusCode = jhc.post("producoes/" + encomenda.getNomeFabricante() + "/" + encomenda.getNomeProduto() + "/encomendas", encomenda);
            if (statusCode >= 200 && statusCode < 300) {
                return REPLY_OK;
            }
            return REPLY_INVALID;
        }
        else {
            System.out.println("Mão existem ofertas de produção correspondentes");
            return REPLY_INVALID;
        }
    }



    private Thread periodoNegociacao(long waitSeconds, Producao producao, String nomeFabricante) {
        return new Thread(() -> {
            try {
                Thread.sleep(1000 * waitSeconds);
                System.out.println("\n=========================================================");
                System.out.println(identity + "\n");
                System.out.println("Período de negociação acabou");
                Collection<Encomenda> encomendas = jhc.getCollection(
                        "producoes/" + producao.getNomeFabricante() + "/" + producao.getNomeProduto() + "/encomendas", Encomenda.class);
                // Heurística da razão para determinar encomendas aceites
                ArrayList<Encomenda> aceites = new ArrayList<>();
                ArrayList<Encomenda> recusadas = new ArrayList<>();
                int quantMin = producao.getQuantidadeMin();
                int quantMax = producao.getQuantidadeMax();
                int quantTotal = 0;

                if(encomendas == null) {
                    System.out.println("Nenhuma oferta de encomenda feita");

                    NotificacaoResultadosFabricante n = NotificacaoResultadosFabricante.newBuilder()
                            .setProduto(producao.getNomeProduto())
                            .build();
                    String topic = "#"+nomeFabricante;
                    socketPUB.sendMore(topic);
                    socketPUB.send(n.toByteArray());
                    System.out.println("Enviou notificação resultados fabricante, tópico: " + topic);

                    jhc.put("producoes/" + producao.getNomeFabricante() + "/" + producao.getNomeProduto() + "/canceladas", producao);
                    return;
                }

                ArrayList<Encomenda> list = new ArrayList<>(encomendas);
                list.sort((Encomenda a,Encomenda b) -> {
                    float ratioA = (float) a.getPrecoPorUnidade() / a.getQuantidade();
                    float ratioB = (float) b.getPrecoPorUnidade() / b.getQuantidade();
                    return Float.compare(ratioA, ratioB);
                });


                for (Encomenda e : list) {
                    int quant = e.getQuantidade();
                    if (quant > quantMax) {
                        recusadas.add(e);
                        continue;
                    }
                    if (quantTotal + quant > quantMax) {
                        recusadas.add(e);
                    } else {
                        quantTotal += quant;
                        aceites.add(e);
                    }
                }

                if(quantTotal < quantMin) {
                    System.out.println("Quantidade mínima não atingida");

                    System.out.println("Notifica importadores participantes:");
                    for (Encomenda e : encomendas) {
                        NotificacaoResultadosImportador n = encomendaToNotif(e, false);
                        socketPUB.sendMore(e.getNomeImportador());
                        socketPUB.send(n.toByteArray());
                        System.out.println("- Enviou notificação resultados importador, tópico: " + e.getNomeImportador());
                        jhc.put("producoes/" + e.getNomeFabricante() + "/" + e.getNomeProduto() + "/encomendas/recusadas", e);
                    }

                    NotificacaoResultadosFabricante n = NotificacaoResultadosFabricante.newBuilder()
                            .setProduto(producao.getNomeProduto())
                            .build();
                    String topic = "#"+nomeFabricante;
                    socketPUB.sendMore(topic);
                    socketPUB.send(n.toByteArray());
                    System.out.println("Envia notificação resultados fabricante, tópico: " + topic);

                    jhc.put("producoes/" + producao.getNomeFabricante() + "/" + producao.getNomeProduto() + "/canceladas", producao);
                } else {
                    System.out.println("Quantidade a produzir: " + quantTotal);
                    System.out.println("Encomendas aceites: " + aceites.size());
                    System.out.println("Encomendas recusadas: " + recusadas.size());

                    for (Encomenda e : aceites) {
                        System.out.println("Notifica importadores participantes aceites:");
                        NotificacaoResultadosImportador n = encomendaToNotif(e, true);
                        socketPUB.sendMore(e.getNomeImportador());
                        socketPUB.send(n.toByteArray());
                        System.out.println("- Enviou notificação resultados importador, tópico: " + e.getNomeImportador());
                        jhc.put("producoes/" + e.getNomeFabricante() + "/" + e.getNomeProduto() + "/encomendas/aceites", e);
                    }
                    for (Encomenda e : recusadas) {
                        System.out.println("Notifica importadores participantes recusados:");
                        NotificacaoResultadosImportador n = encomendaToNotif(e, false);
                        socketPUB.sendMore(e.getNomeImportador());
                        socketPUB.send(n.toByteArray());
                        System.out.println("- Enviou notificação resultados importador, tópico: " + e.getNomeImportador());
                        jhc.put("producoes/" + e.getNomeFabricante() + "/" + e.getNomeProduto() + "/encomendas/recusadas", e);
                    }

                    System.out.println("Notifica fabricante:");
                    ArrayList<OfertaEncomendaRequest> aceitesR = new ArrayList<>();
                    for (Encomenda e : aceites) {
                        aceitesR.add(OfertaEncomendaRequest.newBuilder()
                                .setFabricante(e.getNomeImportador()) // é assim
                                .setPreco(e.getPrecoPorUnidade())
                                .setProduto(e.getNomeProduto())
                                .setQuant(e.getQuantidade())
                                .build()
                        );
                    }
                    NotificacaoResultadosFabricante n = NotificacaoResultadosFabricante.newBuilder()
                            .setProduto(producao.getNomeProduto())
                            .addAllEncomendas(aceitesR)
                            .build();
                    String topic = "#"+nomeFabricante;
                    socketPUB.sendMore(topic);
                    socketPUB.send(n.toByteArray());
                    System.out.println("Envia notificação resultados fabricante, tópico: " + topic);

                    jhc.put("producoes/" + producao.getNomeFabricante() + "/" + producao.getNomeProduto() + "/aceites", producao);
                }
            } catch (Exception e){
                e.printStackTrace();
            }
        });
    }

    private static NotificacaoResultadosImportador encomendaToNotif(Encomenda e, boolean aceite) {
        return NotificacaoResultadosImportador.newBuilder()
                .setAceite(aceite)
                .setFabricante(e.getNomeFabricante())
                .setPreco(e.getPrecoPorUnidade())
                .setProduto(e.getNomeProduto())
                .setQuant(e.getQuantidade())
                .build();
    }

    private static NotificacaoOfertaProducao prodRequestToNotif(OfertaProducaoRequest prodRequest) {
        LocalDateTime inicio = LocalDateTime.now();
        LocalDateTime fim = inicio.plusSeconds(prodRequest.getDuracaoS());
        return NotificacaoOfertaProducao.newBuilder()
                .setProduto(prodRequest.getProduto())
                .setQuantMax(prodRequest.getQuantMax())
                .setQuantMin(prodRequest.getQuantMin())
                .setPrecoUniMin(prodRequest.getPrecoUniMin())
                .setDataInicial(inicio.format(DateTimeFormatter.ISO_DATE_TIME))
                .setDataFinal(fim.format(DateTimeFormatter.ISO_DATE_TIME))
                .build();
    }


}
